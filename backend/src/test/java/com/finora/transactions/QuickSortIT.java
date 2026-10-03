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
import com.finora.repository.UserMerchantCategoryResolutionRepository;
import com.finora.repository.UserRepository;
import com.finora.security.JwtService;
import com.finora.testsupport.TestSessions;
import com.finora.util.CounterpartyType;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Quick sort's endpoints through the real security chain, the real answer path and the database. */
class QuickSortIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private UserMerchantCategoryResolutionRepository resolutionRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    @Autowired private MeterRegistry meterRegistry;
    private final ObjectMapper mapper = new ObjectMapper();

    private User user;
    private UUID accountId;
    private UUID otherId;
    private LocalDate nextDate = LocalDate.of(2026, 9, 30);

    @BeforeEach
    void setUp() {
        user = newUser();
        accountId = newAccount(user.getId());
        otherId = category(user.getId(), "Other");
        category(user.getId(), "Personal Transfer");
    }

    @Test
    void answeringFilesEveryWaitingPaymentOfThatPayeeAndRemembersIt() throws Exception {
        UUID a = seed(user.getId(), accountId, "vpa:samplea", 100);
        UUID b = seed(user.getId(), accountId, "vpa:samplea", 100);
        UUID c = seed(user.getId(), accountId, "vpa:samplea", 100);

        JsonNode answer = data(post("/api/v1/transactions/quick-sort/answer",
                "{\"anchorTransactionId\":\"" + a + "\",\"category\":\"Groceries\",\"kind\":\"SHOP\"}", user));

        assertThat(answer.get("filed").asInt()).isEqualTo(3);
        for (UUID id : List.of(a, b, c)) {
            Transaction t = reload(id);
            assertThat(t.isCategoryManuallySet()).isTrue();
            assertThat(t.isNeedsCategoryReview()).isFalse();
            assertThat(t.getQuickSortedAt()).isNotNull();
        }
        assertThat(questionIds(batch(0))).doesNotContain("vpa:samplea|EXPENSE");
        assertThat(resolutionRepository.findByUserIdAndCounterpartyKeyAndDirection(
                user.getId(), "vpa:samplea", Transaction.Type.EXPENSE)).isPresent();
    }

    @Test
    void answeringNeverRefilesAPaymentOfThePayeeThatWasNotWaiting() throws Exception {
        UUID a = seed(user.getId(), accountId, "vpa:samplea", 100);
        UUID b = seed(user.getId(), accountId, "vpa:samplea", 100);
        UUID filedByRule = seed(user.getId(), accountId, "vpa:samplea", 100);
        UUID shoppingId = category(user.getId(), "Shopping");
        Transaction ruled = reload(filedByRule);
        ruled.setNeedsCategoryReview(false);
        ruled.setDecisionSource(Transaction.DecisionSource.KEYWORD_MATCH);
        ruled.setCategoryId(shoppingId);
        transactionRepository.save(ruled);

        JsonNode answer = data(post("/api/v1/transactions/quick-sort/answer",
                "{\"anchorTransactionId\":\"" + a + "\",\"category\":\"Groceries\",\"kind\":\"SHOP\"}", user));

        // The question showed the two waiting payments; only those are filed.
        assertThat(answer.get("filed").asInt()).isEqualTo(2);
        assertThat(reload(b).isNeedsCategoryReview()).isFalse();
        Transaction untouched = reload(filedByRule);
        assertThat(untouched.getCategoryId()).isEqualTo(shoppingId);
        assertThat(untouched.getDecisionSource()).isEqualTo(Transaction.DecisionSource.KEYWORD_MATCH);
        assertThat(untouched.getQuickSortedAt()).isNull();
        // The payee is still remembered for future imports.
        assertThat(resolutionRepository.findByUserIdAndCounterpartyKeyAndDirection(
                user.getId(), "vpa:samplea", Transaction.Type.EXPENSE)).isPresent();
    }

    @Test
    void answeringARowOnAKeyThatNamesNoOneFilesOnlyThatRow() throws Exception {
        UUID a = seed(user.getId(), accountId, "cut:samplepay.12", 100);
        UUID b = seed(user.getId(), accountId, "cut:samplepay.12", 90);

        JsonNode answer = data(post("/api/v1/transactions/quick-sort/answer",
                "{\"anchorTransactionId\":\"" + a + "\",\"category\":\"Dining\",\"kind\":\"SHOP\"}", user));

        assertThat(answer.get("filed").asInt()).isEqualTo(1);
        assertThat(reload(a).isNeedsCategoryReview()).isFalse();
        assertThat(reload(b).isNeedsCategoryReview()).isTrue();
        assertThat(reload(b).getQuickSortedAt()).isNull();
    }

    @Test
    void answeringOnPurposeWithPersonalTransferResolvesIt() throws Exception {
        UUID a = seed(user.getId(), accountId, "vpa:ravi", 300);

        post("/api/v1/transactions/quick-sort/answer",
                "{\"anchorTransactionId\":\"" + a + "\",\"category\":\"Personal Transfer\",\"kind\":\"PERSON_PAID\"}", user);

        assertThat(reload(a).isNeedsCategoryReview()).isFalse();
        assertThat(reload(a).getDecisionSource()).isEqualTo(Transaction.DecisionSource.MANUAL);
    }

    @Test
    void answeringARowAlreadyChosenElsewhereDoesNotFail() throws Exception {
        UUID a = seed(user.getId(), accountId, "vpa:samplea", 100);
        UUID b = seed(user.getId(), accountId, "vpa:samplea", 100);
        Transaction chosen = reload(b);
        chosen.setCategoryManuallySet(true);
        chosen.setNeedsCategoryReview(false);
        transactionRepository.save(chosen);

        ResponseEntity<String> response = post("/api/v1/transactions/quick-sort/answer",
                "{\"anchorTransactionId\":\"" + a + "\",\"category\":\"Groceries\",\"kind\":\"SHOP\"}", user);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(data(response).get("filed").asInt()).isEqualTo(1);
        assertThat(reload(b).getQuickSortedAt()).isNull();
    }

    @Test
    void anotherUsersRowCannotBeAnswered() throws Exception {
        User other = newUser();
        UUID theirs = seed(other.getId(), newAccount(other.getId()), "vpa:samplea", 100);

        ResponseEntity<String> response = post("/api/v1/transactions/quick-sort/answer",
                "{\"anchorTransactionId\":\"" + theirs + "\",\"category\":\"Groceries\",\"kind\":\"SHOP\"}", user);

        assertThat(response.getStatusCode().is4xxClientError()).isTrue();
        assertThat(reload(theirs).isNeedsCategoryReview()).isTrue();
    }

    @Test
    void keepRestClearsTheFlagAndKeepsTheCategory() throws Exception {
        UUID a = seed(user.getId(), accountId, "vpa:samplea", 100);
        UUID b = seed(user.getId(), accountId, "vpa:sampleb", 50);
        long versionBefore = reload(a).getVersion();

        JsonNode result = data(post("/api/v1/transactions/quick-sort/keep-rest",
                "{\"transactionIds\":[\"" + a + "\",\"" + b + "\"]}", user));

        assertThat(result.get("cleared").asInt()).isEqualTo(2);
        Transaction t = reload(a);
        assertThat(t.isNeedsCategoryReview()).isFalse();
        assertThat(t.getCategoryId()).isEqualTo(otherId);
        assertThat(t.getDecisionSource()).isEqualTo(Transaction.DecisionSource.MERCHANT_DEFAULT);
        assertThat(t.isCategoryManuallySet()).isFalse();
        assertThat(t.getVersion()).isEqualTo(versionBefore + 1);
    }

    @Test
    void keepRestSkipsRowsNotWaitingChosenOrNotOwned() throws Exception {
        User other = newUser();
        UUID theirs = seed(other.getId(), newAccount(other.getId()), "vpa:samplea", 100);
        UUID chosen = seed(user.getId(), accountId, "vpa:sampleb", 100);
        Transaction c = reload(chosen);
        c.setCategoryManuallySet(true);
        transactionRepository.save(c);
        UUID notWaiting = seed(user.getId(), accountId, "vpa:samplec", 100);
        Transaction n = reload(notWaiting);
        n.setNeedsCategoryReview(false);
        transactionRepository.save(n);

        JsonNode result = data(post("/api/v1/transactions/quick-sort/keep-rest",
                "{\"transactionIds\":[\"" + theirs + "\",\"" + chosen + "\",\"" + notWaiting + "\"]}", user));

        assertThat(result.get("cleared").asInt()).isZero();
        assertThat(reload(theirs).isNeedsCategoryReview()).isTrue();
        assertThat(reload(chosen).isNeedsCategoryReview()).isTrue();
    }

    @Test
    void keepRestRejectsMoreThanTwoThousandIds() throws Exception {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 2001; i++) ids.add("\"" + UUID.randomUUID() + "\"");

        ResponseEntity<String> response = post("/api/v1/transactions/quick-sort/keep-rest",
                "{\"transactionIds\":[" + String.join(",", ids) + "]}", user);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void changingAnAnsweredRowLaterIsCounted() throws Exception {
        UUID answered = seed(user.getId(), accountId, "vpa:samplea", 100);
        UUID never = seed(user.getId(), accountId, "vpa:sampleb", 100);
        post("/api/v1/transactions/quick-sort/answer",
                "{\"anchorTransactionId\":\"" + answered + "\",\"category\":\"Groceries\",\"kind\":\"SHOP\"}", user);
        double before = count("finora.quick_sort.answer_changed_later");

        send(HttpMethod.PATCH, "/api/v1/transactions/" + never + "/category", "{\"category\":\"Dining\"}", user);
        assertThat(count("finora.quick_sort.answer_changed_later")).isEqualTo(before);

        send(HttpMethod.PATCH, "/api/v1/transactions/" + answered + "/category", "{\"category\":\"Dining\"}", user);
        assertThat(count("finora.quick_sort.answer_changed_later")).isEqualTo(before + 1);
    }

    @Test
    void countersMove() throws Exception {
        UUID a = seed(user.getId(), accountId, "vpa:samplea", 100);
        UUID b = seed(user.getId(), accountId, "vpa:sampleb", 5);
        double batches = count("finora.quick_sort.batches_shown");
        double shown = count("finora.quick_sort.questions_shown");
        double answered = meterRegistry.find("finora.quick_sort.answered").tag("kind", "SHOP").counter() == null ? 0
                : meterRegistry.find("finora.quick_sort.answered").tag("kind", "SHOP").counter().count();
        double more = count("finora.quick_sort.more_taken");
        double stop = count("finora.quick_sort.stop_asking_taken");
        double stopRows = count("finora.quick_sort.stop_asking_rows");

        int size = batch(0).get("questions").size();
        post("/api/v1/transactions/quick-sort/answer",
                "{\"anchorTransactionId\":\"" + a + "\",\"category\":\"Groceries\",\"kind\":\"SHOP\"}", user);
        post("/api/v1/transactions/quick-sort/more", "{}", user);
        post("/api/v1/transactions/quick-sort/keep-rest", "{\"transactionIds\":[\"" + b + "\"]}", user);

        assertThat(count("finora.quick_sort.batches_shown")).isEqualTo(batches + 1);
        assertThat(count("finora.quick_sort.questions_shown")).isEqualTo(shown + size);
        assertThat(meterRegistry.find("finora.quick_sort.answered").tag("kind", "SHOP").counter().count())
                .isEqualTo(answered + 1);
        assertThat(count("finora.quick_sort.more_taken")).isEqualTo(more + 1);
        assertThat(count("finora.quick_sort.stop_asking_taken")).isEqualTo(stop + 1);
        assertThat(count("finora.quick_sort.stop_asking_rows")).isEqualTo(stopRows + 1);
    }

    @Test
    void aPreviewIsNotCountedAsABatchShown() throws Exception {
        seed(user.getId(), accountId, "vpa:samplea", 100);
        double batches = count("finora.quick_sort.batches_shown");

        JsonNode preview = data(restTemplate.exchange("/api/v1/transactions/quick-sort?preview=true", HttpMethod.GET,
                new HttpEntity<>(bearerFor(user)), String.class));

        assertThat(preview.get("questions").size()).isEqualTo(1);
        assertThat(count("finora.quick_sort.batches_shown")).isEqualTo(batches);
    }

    @Test
    void theBatchIsTheUsersOwnWaitingRows() throws Exception {
        seed(user.getId(), accountId, "vpa:samplea", 100);
        User other = newUser();
        seed(other.getId(), newAccount(other.getId()), "vpa:theirs", 900);

        JsonNode batch = batch(0);

        assertThat(questionIds(batch)).containsExactly("vpa:samplea|EXPENSE");
        assertThat(batch.get("waitingTotal").decimalValue()).isEqualByComparingTo("100");
    }

    // --- helpers ---

    private JsonNode batch(int skip) throws Exception {
        return data(restTemplate.exchange("/api/v1/transactions/quick-sort?skip=" + skip, HttpMethod.GET,
                new HttpEntity<>(bearerFor(user)), String.class));
    }

    private List<String> questionIds(JsonNode batch) {
        List<String> ids = new ArrayList<>();
        batch.get("questions").forEach(q -> ids.add(q.get("id").asText()));
        return ids;
    }

    private ResponseEntity<String> post(String path, String body, User who) {
        return send(HttpMethod.POST, path, body, who);
    }

    private ResponseEntity<String> send(HttpMethod method, String path, String body, User who) {
        return restTemplate.exchange(path, method, new HttpEntity<>(body, bearerFor(who)), String.class);
    }

    private JsonNode data(ResponseEntity<String> response) throws Exception {
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
        return mapper.readTree(response.getBody()).get("data");
    }

    private double count(String name) {
        Counter c = meterRegistry.find(name).counter();
        return c == null ? 0 : c.count();
    }

    private HttpHeaders bearerFor(User who) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestSessions.accessTokenFor(jwtService, refreshTokens, who));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private User newUser() {
        User u = new User();
        u.setEmail("quick-sort-it-" + UUID.randomUUID() + "@example.com");
        u.setPasswordHash("irrelevant-for-this-test");
        u.setFullName("Quick Sort Test User");
        u.setAccountScope(User.SCOPE_USER);
        u.setPhoneVerified(true);
        return userRepository.save(u);
    }

    private UUID newAccount(UUID owner) {
        Account account = new Account();
        account.setUserId(owner);
        account.setName("Test Savings");
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(BigDecimal.valueOf(10000));
        return accountRepository.save(account).getId();
    }

    private UUID category(UUID owner, String name) {
        Category c = new Category();
        c.setUserId(owner);
        c.setName(name);
        return categoryRepository.save(c).getId();
    }

    private UUID seed(UUID owner, UUID account, String key, double amount) {
        Transaction t = new Transaction();
        t.setUserId(owner);
        t.setAccountId(account);
        t.setCategoryId(owner.equals(user.getId()) ? otherId : null);
        t.setTxnDate(nextDate);
        nextDate = nextDate.minusDays(1);
        t.setAmount(BigDecimal.valueOf(amount));
        t.setTxnType(Transaction.Type.EXPENSE);
        t.setDescription("UPI/SAMPLE PAYEE/" + key + "/900011112200"); // synthetic-ok
        t.setCounterpartyKey(key);
        t.setCounterpartyType(CounterpartyType.BUSINESS);
        t.setDecisionSource(Transaction.DecisionSource.MERCHANT_DEFAULT);
        t.setNeedsCategoryReview(true);
        return transactionRepository.save(t).getId();
    }

    private Transaction reload(UUID id) {
        return transactionRepository.findById(id).orElseThrow();
    }
}
