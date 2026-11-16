package com.finora.aisort;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
import com.finora.testsupport.TestSessions;
import com.finora.entity.Account;
import com.finora.entity.Category;
import com.finora.entity.StatementImport;
import com.finora.entity.Transaction;
import com.finora.entity.User;
import com.finora.integrations.anthropic.LlmClient;
import com.finora.integrations.anthropic.LlmClient.LlmCompletion;
import com.finora.integrations.anthropic.LlmClient.LlmRequest;
import com.finora.integrations.anthropic.LlmClient.ToolUse;
import com.finora.repository.AccountRepository;
import com.finora.repository.CategoryRepository;
import com.finora.repository.RefreshTokenRepository;
import com.finora.repository.StatementImportRepository;
import com.finora.repository.TransactionRepository;
import com.finora.repository.UserRepository;
import com.finora.security.JwtService;
import com.finora.service.FynAvailabilityGuard;
import com.finora.service.FynCostGovernanceService;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * A tap on the Done step's AI card, end to end over HTTP: the run, payee by payee, the most money
 * first, with the model stubbed (spec 2026-11-14-ai-sort-after-import, "Sorting").
 */
class AiSortRunIT extends AbstractIntegrationTest {

    private static final String AI_CATEGORY = "Food & Dining";

    @Autowired private TestRestTemplate rest;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    @Autowired private UserRepository users;
    @Autowired private AccountRepository accounts;
    @Autowired private CategoryRepository categories;
    @Autowired private StatementImportRepository statementImports;
    @Autowired private TransactionRepository transactions;
    @Autowired private AiSortRunRepository runs;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper mapper;
    @Autowired private com.finora.imports.ImportRuleLearningService importLearning;
    @Autowired private AiSortService aiSortService;

    @MockitoBean private LlmClient llm;
    @MockitoBean private FynAvailabilityGuard guard;
    @MockitoBean private FynCostGovernanceService costs;

    private User user;
    private UUID accountId;
    private UUID otherId;
    private UUID importId;
    private LocalDate nextDate = LocalDate.of(2026, 11, 10);
    /** The order of RESOLVE_CATEGORY calls, 0-based; a hook runs before each is answered. */
    private final AtomicInteger resolveCalls = new AtomicInteger();
    private final AtomicBoolean capReached = new AtomicBoolean();
    private volatile IntConsumer beforeResolve = i -> { };
    private final Set<Integer> silentCalls = new HashSet<>();
    /** RESOLVE_CATEGORY calls (0-based) that fail as an unreachable model would. */
    private final Set<Integer> failingCalls = new HashSet<>();
    /** Every model call fails: the AI cannot be reached. */
    private final AtomicBoolean unreachable = new AtomicBoolean();
    private final AtomicInteger modelCalls = new AtomicInteger();

    @BeforeEach
    void setUp() {
        user = newUser(Boolean.TRUE);
        accountId = newAccount(user.getId());
        otherId = category(user.getId(), "Other");
        importId = newImport(user.getId(), accountId);

        when(guard.categorizationOffered()).thenReturn(true);
        when(guard.categorizationAvailableFor(any())).thenAnswer(inv -> !capReached.get()
                && Boolean.TRUE.equals(users.findById(inv.getArgument(0)).orElseThrow().getAiProcessingConsent()));
        when(costs.userDailyCapReached(any())).thenAnswer(inv -> capReached.get());
        when(llm.complete(any())).thenAnswer(inv -> answer(inv.getArgument(0)));
    }

    private LlmCompletion answer(LlmRequest request) {
        modelCalls.incrementAndGet();
        if (unreachable.get()) throw new RuntimeException("Could not reach Anthropic.");
        String tool = request.tools().get(0).name();
        if ("UNDERSTAND_MERCHANT".equals(tool)) {
            return new LlmCompletion(null, List.of(new ToolUse("u", tool, Map.of("understanding", "A shop"))),
                    "claude-haiku-4-5-20251001", 40, 10, "tool_use");
        }
        int call = resolveCalls.getAndIncrement();
        beforeResolve.accept(call);
        if (failingCalls.contains(call)) throw new RuntimeException("Could not reach Anthropic.");
        if (silentCalls.contains(call)) {
            return new LlmCompletion("no idea", List.of(), "claude-haiku-4-5-20251001", 40, 10, "end_turn");
        }
        return new LlmCompletion(null, List.of(new ToolUse("r", tool, Map.of("category", AI_CATEGORY))),
                "claude-haiku-4-5-20251001", 40, 10, "tool_use");
    }

    @Test
    void sortsTheBiggestPayeeFirstAndFilesAsAGuess() throws Exception {
        String small = shop();
        String big = shop();
        UUID s1 = row(importId, small, 100);
        UUID b1 = row(importId, big, 500);
        UUID b2 = row(importId, big, 400);

        JsonNode run = finished(start("SORT"));

        assertThat(run.get("status").asText()).isEqualTo("DONE");
        assertThat(run.get("paymentsTotal").asInt()).isEqualTo(3);
        assertThat(run.get("paymentsSorted").asInt()).isEqualTo(3);
        assertThat(run.get("paymentsLeft").asInt()).isZero();
        for (UUID id : List.of(s1, b1, b2)) {
            Transaction t = transactions.findById(id).orElseThrow();
            assertThat(t.getDecisionSource()).isEqualTo(Transaction.DecisionSource.AI_FALLBACK);
            assertThat(categoryName(t)).isEqualTo(AI_CATEGORY);
            assertThat(t.isNeedsCategoryReview()).isTrue();
        }
        // The big payee was asked first: its saved answer is the earlier one.
        assertThat(firstSortedKey()).isEqualTo(com.finora.util.CounterpartyTyping.of(big).key());
    }

    @Test
    void alsoFilesThatPayeesEarlierWaitingRows() throws Exception {
        String payee = shop();
        UUID earlierImport = newImport(user.getId(), accountId);
        UUID earlier = row(earlierImport, payee, 50);
        row(importId, payee, 300);

        JsonNode run = finished(start("SORT"));

        assertThat(run.get("paymentsSorted").asInt()).isEqualTo(1);
        assertThat(categoryName(transactions.findById(earlier).orElseThrow())).isEqualTo(AI_CATEGORY);
    }

    @Test
    void stopsWhenAiIsTurnedOffMidRun() throws Exception {
        row(importId, shop(), 500);
        row(importId, shop(), 100);
        beforeResolve = i -> {
            if (i == 0) jdbc.update("UPDATE users SET ai_processing_consent = false WHERE id = ?", user.getId());
        };

        JsonNode run = finished(start("SORT"));

        assertThat(run.get("status").asText()).isEqualTo("STOPPED_AI_OFF");
        assertThat(run.get("paymentsSorted").asInt()).isEqualTo(1);
        assertThat(resolveCalls.get()).isEqualTo(1);
    }

    @Test
    void stopsAtTheDailyCap() throws Exception {
        row(importId, shop(), 500);
        row(importId, shop(), 100);
        beforeResolve = i -> capReached.set(true);

        JsonNode run = finished(start("SORT"));

        assertThat(run.get("status").asText()).isEqualTo("STOPPED_LIMIT");
        assertThat(run.get("paymentsSorted").asInt()).isEqualTo(1);
        assertThat(run.get("paymentsLeft").asInt()).isEqualTo(1);
    }

    @Test
    void leavesAPayeeTheCustomerAnsweredMidRun() throws Exception {
        row(importId, shop(), 500);
        String second = shop();
        UUID answered = row(importId, second, 100);
        UUID groceries = category(user.getId(), "Groceries");
        beforeResolve = i -> {
            if (i == 0) {
                jdbc.update("UPDATE transactions SET category_id = ?, category_manually_set = true, "
                        + "needs_category_review = false WHERE id = ?", groceries, answered);
            }
        };

        finished(start("SORT"));

        Transaction t = transactions.findById(answered).orElseThrow();
        assertThat(t.getCategoryId()).isEqualTo(groceries);
        assertThat(t.getDecisionSource()).isNotEqualTo(Transaction.DecisionSource.AI_FALLBACK);
    }

    @Test
    void aPayeeTheCustomerAnsweredMidRunIsNotLeftForThem() throws Exception {
        row(importId, shop(), 500);
        UUID answered = row(importId, shop(), 100);
        UUID groceries = category(user.getId(), "Groceries");
        beforeResolve = i -> {
            if (i == 0) {
                jdbc.update("UPDATE transactions SET category_id = ?, category_manually_set = true, "
                        + "needs_category_review = false WHERE id = ?", groceries, answered);
            }
        };

        JsonNode run = finished(start("SORT"));

        assertThat(run.get("status").asText()).isEqualTo("DONE");
        assertThat(run.get("paymentsSorted").asInt()).isEqualTo(1);
        // Was 1 ("1 left for you") although the customer had answered it themselves.
        assertThat(run.get("paymentsLeft").asInt()).isZero();
    }

    @Test
    void anUnreachableAiStopsTheRun_andATapTriesItAgain() throws Exception {
        List<UUID> rows = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) rows.add(row(importId, shop(), 500 - i));
        unreachable.set(true);

        JsonNode stopped = finished(start("SORT"));

        // Not "DONE / AI couldn't sort these": the AI never judged them.
        assertThat(stopped.get("status").asText()).isEqualTo("STOPPED");
        assertThat(stopped.get("paymentsSorted").asInt()).isZero();
        assertThat(stopped.get("paymentsLeft").asInt()).isEqualTo(5);
        // It gave up after three payees in a row failed, rather than waiting out every payee.
        assertThat(modelCalls.get()).isEqualTo(AiSortRunner.MAX_FAILURES_IN_A_ROW);
        assertThat(offer().get("show").asBoolean()).isTrue();

        unreachable.set(false);
        JsonNode retried = finished(start("SORT"));

        assertThat(retried.get("id").asText()).isEqualTo(stopped.get("id").asText());
        assertThat(retried.get("status").asText()).isEqualTo("DONE");
        assertThat(retried.get("paymentsTotal").asInt()).isEqualTo(5);
        assertThat(retried.get("paymentsSorted").asInt()).isEqualTo(5);
        assertThat(retried.get("paymentsLeft").asInt()).isZero();
        assertThat(runs.findById(UUID.fromString(retried.get("id").asText())).orElseThrow().getAttempt()).isEqualTo(2);
        for (UUID id : rows) {
            assertThat(transactions.findById(id).orElseThrow().getDecisionSource())
                    .isEqualTo(Transaction.DecisionSource.AI_FALLBACK);
        }
    }

    @Test
    void onePayeesFailedCallLeavesTheRunStopped_withThatPayeeLeft() throws Exception {
        row(importId, shop(), 500);
        UUID failed = row(importId, shop(), 300);
        row(importId, shop(), 100);
        failingCalls.add(1);

        JsonNode run = finished(start("SORT"));

        assertThat(run.get("status").asText()).isEqualTo("STOPPED");
        assertThat(run.get("paymentsSorted").asInt()).isEqualTo(2);
        assertThat(run.get("paymentsLeft").asInt()).isEqualTo(1);
        assertThat(transactions.findById(failed).orElseThrow().getCategoryId()).isEqualTo(otherId);

        // Tried again: the counts stay the import's -- the AI has now sorted all 3, not "1".
        failingCalls.clear();
        JsonNode retried = finished(start("SORT"));

        assertThat(retried.get("status").asText()).isEqualTo("DONE");
        assertThat(retried.get("paymentsTotal").asInt()).isEqualTo(3);
        assertThat(retried.get("paymentsDone").asInt()).isEqualTo(3);
        assertThat(retried.get("paymentsSorted").asInt()).isEqualTo(3);
        assertThat(retried.get("paymentsLeft").asInt()).isZero();
        assertThat(transactions.findById(failed).orElseThrow().getDecisionSource())
                .isEqualTo(Transaction.DecisionSource.AI_FALLBACK);
    }

    @Test
    void aRunCutOffMidwayStopsItsWorker_andATapTriesItAgain() throws Exception {
        row(importId, shop(), 500);
        UUID second = row(importId, shop(), 300);
        UUID third = row(importId, shop(), 100);
        // While the first payee is with the model, the run is read as cut off (quiet for 10 minutes).
        beforeResolve = i -> {
            if (i == 0) {
                jdbc.update("UPDATE ai_sort_runs SET updated_at = now() - interval '11 minutes' WHERE user_id = ?",
                        user.getId());
                // What GET /ai-sort/runs/{id} does (the card's poll), on this thread.
                aiSortService.run(user.getId(), runs.findAll().stream().filter(r -> r.getUserId().equals(user.getId()))
                        .findFirst().orElseThrow().getId());
            }
        };

        JsonNode cut = finished(start("SORT"));
        Thread.sleep(1_000);

        assertThat(cut.get("status").asText()).isEqualTo("STOPPED");
        // The worker stopped before its next payee: no further model call, nothing more filed.
        assertThat(resolveCalls.get()).isEqualTo(1);
        assertThat(transactions.findById(second).orElseThrow().getCategoryId()).isEqualTo(otherId);
        assertThat(transactions.findById(third).orElseThrow().getCategoryId()).isEqualTo(otherId);
        assertThat(json(cut.get("id").asText()).get("status").asText()).isEqualTo("STOPPED");

        beforeResolve = i -> { };
        JsonNode retried = finished(start("SORT"));

        assertThat(retried.get("status").asText()).isEqualTo("DONE");
        assertThat(transactions.findById(second).orElseThrow().getDecisionSource())
                .isEqualTo(Transaction.DecisionSource.AI_FALLBACK);
        assertThat(transactions.findById(third).orElseThrow().getDecisionSource())
                .isEqualTo(Transaction.DecisionSource.AI_FALLBACK);
    }

    @Test
    void theOneMoreQuestionIsAnsweredOnce_whenBothAnswersArriveTogether() throws Exception {
        switchTo(Boolean.FALSE);
        row(importId, shop(), 500);
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var noThanks = pool.submit(() -> { go.await(); return post("NO_THANKS").getStatusCode(); });
            var turnOn = pool.submit(() -> { go.await(); return post("TURN_ON_AND_SORT").getStatusCode(); });
            go.countDown();
            HttpStatus no = HttpStatus.valueOf(noThanks.get().value());
            HttpStatus on = HttpStatus.valueOf(turnOn.get().value());

            assertThat(List.of(no, on)).containsExactlyInAnyOrder(HttpStatus.OK, HttpStatus.BAD_REQUEST);
            User after = users.findById(user.getId()).orElseThrow();
            assertThat(after.getAiImportQuestionAt()).isNotNull();
            assertThat(after.getAiProcessingConsent()).isEqualTo(on == HttpStatus.OK);
        } finally {
            pool.shutdownNow();
        }
        Thread.sleep(500);
    }

    @Test
    void aMissingImportIdIsRefused_notAServerError() {
        String body = "{\"statementImportIds\":[null],\"choice\":\"SORT\"}";
        ResponseEntity<String> start = rest.exchange("/api/v1/ai-sort/runs", HttpMethod.POST,
                new HttpEntity<>(body, headers()), String.class);
        ResponseEntity<String> offer = rest.exchange("/api/v1/ai-sort/offer?statementImportIds=" + importId
                + "&statementImportIds=", HttpMethod.GET, new HttpEntity<>(headers()), String.class);

        assertThat(start.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(offer.getStatusCode().is5xxServerError()).as(offer.getBody()).isFalse();
    }

    @Test
    void theNextImportAsksAboutTheAisAnswer_andLearnsOnlyAChange() throws Exception {
        // AI sort break-test, 2026-11-16: confirming a later import read the model's answer from a
        // table nothing writes, so the answer was filed without a question and learned as the
        // customer's own. The decision now reads the customer's saved AI answer.
        String narration = shop();
        row(importId, narration, 500);
        finished(start("SORT"));

        var leftAlone = importLearning.recordDecision(user.getId(), confirmedRow(narration, AI_CATEGORY));
        var changed = importLearning.recordDecision(user.getId(), confirmedRow(narration, "Groceries"));

        assertThat(leftAlone.unresolvedGuess()).isTrue();
        assertThat(leftAlone.worthLearning()).isFalse();
        assertThat(changed.unresolvedGuess()).isFalse();
        assertThat(changed.worthLearning()).isTrue();
    }

    private static com.finora.dto.ImportDto.ConfirmedRow confirmedRow(String narration, String category) {
        return new com.finora.dto.ImportDto.ConfirmedRow(LocalDate.of(2026, 12, 1), narration, BigDecimal.valueOf(500),
                "EXPENSE", category, true, com.finora.service.CategorizationService.AI_FALLBACK_SOURCE, null, false,
                null, null);
    }

    @Test
    void countsAPayeeTheAiCouldNotPlaceAsLeft() throws Exception {
        row(importId, shop(), 500);
        UUID unplaced = row(importId, shop(), 100);
        silentCalls.add(1);

        JsonNode run = finished(start("SORT"));

        assertThat(run.get("status").asText()).isEqualTo("DONE");
        assertThat(run.get("paymentsSorted").asInt()).isEqualTo(1);
        assertThat(run.get("paymentsLeft").asInt()).isEqualTo(1);
        assertThat(transactions.findById(unplaced).orElseThrow().getCategoryId()).isEqualTo(otherId);
    }

    @Test
    void secondTapReturnsTheSameRun() throws Exception {
        row(importId, shop(), 500);

        String first = start("SORT").get("id").asText();
        String second = start("SORT").get("id").asText();

        assertThat(second).isEqualTo(first);
        assertThat(runs.findAll()).filteredOn(r -> r.getUserId().equals(user.getId())).hasSize(1);
        finished(json(first));
    }

    @Test
    void askAgainRecordsYesAndSorts() throws Exception {
        switchTo(Boolean.FALSE);
        row(importId, shop(), 500);
        assertThat(offer().get("mode").asText()).isEqualTo("ASK");

        JsonNode run = finished(start("TURN_ON_AND_SORT"));

        User after = users.findById(user.getId()).orElseThrow();
        assertThat(after.getAiProcessingConsent()).isTrue();
        assertThat(after.getAiImportQuestionAt()).isNotNull();
        assertThat(run.get("paymentsSorted").asInt()).isEqualTo(1);
        Integer audits = jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE user_id = ? "
                + "AND action = 'AI_PROCESSING_CONSENT_CHANGED'", Integer.class, user.getId());
        assertThat(audits).isEqualTo(1);
    }

    @Test
    void noThanksRecordsTheQuestionAndNeverOffersAgain() throws Exception {
        switchTo(Boolean.FALSE);
        row(importId, shop(), 500);

        ResponseEntity<String> response = post("NO_THANKS");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        User after = users.findById(user.getId()).orElseThrow();
        assertThat(after.getAiProcessingConsent()).isFalse();
        assertThat(after.getAiImportQuestionAt()).isNotNull();
        assertThat(offer().get("show").asBoolean()).isFalse();
        assertThat(resolveCalls.get()).isZero();
    }

    @Test
    void askAgainIsRefusedOnceAnswered() throws Exception {
        switchTo(Boolean.FALSE);
        row(importId, shop(), 500);
        post("NO_THANKS");

        assertThat(post("TURN_ON_AND_SORT").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(users.findById(user.getId()).orElseThrow().getAiProcessingConsent()).isFalse();
    }

    @Test
    void sortIsRefusedToACustomerWhoSaidNo() throws Exception {
        switchTo(Boolean.FALSE);
        row(importId, shop(), 500);

        assertThat(post("SORT").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resolveCalls.get()).isZero();
    }

    @Test
    void aStaleRunReadsStopped() throws Exception {
        AiSortRun stale = runs.saveAndFlush(AiSortRun.start(user.getId(), UUID.randomUUID().toString(), 4));
        jdbc.update("UPDATE ai_sort_runs SET updated_at = now() - interval '11 minutes' WHERE id = ?", stale.getId());

        JsonNode run = json(stale.getId().toString());

        assertThat(run.get("status").asText()).isEqualTo("STOPPED");
        assertThat(runs.findById(stale.getId()).orElseThrow().getStatus()).isEqualTo(AiSortRun.RunStatus.STOPPED);
    }

    @Test
    void anotherCustomersRunIs404() {
        AiSortRun theirs = runs.saveAndFlush(AiSortRun.start(newUser(Boolean.TRUE).getId(), UUID.randomUUID().toString(), 1));

        ResponseEntity<String> response = rest.exchange("/api/v1/ai-sort/runs/" + theirs.getId(), HttpMethod.GET,
                new HttpEntity<>(headers()), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void tooManyImportIdsIsRefused() {
        StringBuilder ids = new StringBuilder();
        for (int i = 0; i <= AiSortService.MAX_IDS; i++) ids.append(i == 0 ? "" : ",").append(UUID.randomUUID());

        ResponseEntity<String> response = rest.exchange("/api/v1/ai-sort/offer?statementImportIds=" + ids,
                HttpMethod.GET, new HttpEntity<>(headers()), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // --- HTTP ---

    private JsonNode offer() throws Exception {
        return data(rest.exchange("/api/v1/ai-sort/offer?statementImportIds=" + importId, HttpMethod.GET,
                new HttpEntity<>(headers()), String.class));
    }

    private ResponseEntity<String> post(String choice) {
        String body = "{\"statementImportIds\":[\"" + importId + "\"],\"choice\":\"" + choice + "\"}";
        return rest.exchange("/api/v1/ai-sort/runs", HttpMethod.POST, new HttpEntity<>(body, headers()), String.class);
    }

    private JsonNode start(String choice) throws Exception {
        return data(post(choice));
    }

    private JsonNode json(String runId) throws Exception {
        return data(rest.exchange("/api/v1/ai-sort/runs/" + runId, HttpMethod.GET, new HttpEntity<>(headers()), String.class));
    }

    /** Polls the run until it is no longer RUNNING (the work is on its own thread). */
    private JsonNode finished(JsonNode started) throws Exception {
        String id = started.get("id").asText();
        long deadline = System.currentTimeMillis() + 15_000;
        JsonNode run = json(id);
        while ("RUNNING".equals(run.get("status").asText()) && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
            run = json(id);
        }
        assertThat(run.get("status").asText()).as("run finished").isNotEqualTo("RUNNING");
        return run;
    }

    private JsonNode data(ResponseEntity<String> response) throws Exception {
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
        return mapper.readTree(response.getBody()).get("data");
    }

    private HttpHeaders headers() {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(TestSessions.accessTokenFor(jwtService, refreshTokens, user));
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    // --- fixtures ---

    private void switchTo(Boolean consent) {
        User u = users.findById(user.getId()).orElseThrow();
        u.recordAiProcessingConsent(consent, Instant.now());
        user = users.save(u);
    }

    private String firstSortedKey() {
        return jdbc.queryForObject("SELECT counterparty_key FROM user_merchant_category_resolution WHERE user_id = ? "
                + "ORDER BY resolved_at, counterparty_key LIMIT 1", String.class, user.getId());
    }

    private String categoryName(Transaction t) {
        return categories.findById(t.getCategoryId()).map(Category::getName).orElse(null);
    }

    private static String shop() {
        return "UPI-QZX HARDWARE STORE-qzxrun" + UUID.randomUUID().toString().replace("-", "").substring(0, 10)
                + "@ybl-REF881234";
    }

    private UUID row(UUID statementImportId, String narration, double amount) {
        Transaction t = new Transaction();
        t.setUserId(user.getId());
        t.setAccountId(accountId);
        t.setStatementImportId(statementImportId);
        t.setCategoryId(otherId);
        t.setTxnDate(nextDate);
        nextDate = nextDate.minusDays(1);
        t.setAmount(BigDecimal.valueOf(amount));
        t.setTxnType(Transaction.Type.EXPENSE);
        t.setDescription(narration);
        t.applyCounterpartyTyping(narration);
        t.setNeedsCategoryReview(true);
        return transactions.save(t).getId();
    }

    private User newUser(Boolean consent) {
        User u = new User();
        u.setEmail("ai-sort-run-it-" + UUID.randomUUID() + "@example.com");
        u.setPasswordHash("irrelevant");
        u.setFullName("Ai Sort Run IT");
        u.setAccountScope(User.SCOPE_USER);
        u.setPhoneVerified(true);
        if (consent != null) u.recordAiProcessingConsent(consent, Instant.now());
        return users.save(u);
    }

    private UUID newAccount(UUID owner) {
        Account a = new Account();
        a.setUserId(owner);
        a.setName("Ai Sort Run IT Savings");
        a.setAccountType(Account.Type.SAVINGS);
        a.setBalance(BigDecimal.valueOf(1000));
        return accounts.save(a).getId();
    }

    private UUID category(UUID owner, String name) {
        Category c = new Category();
        c.setUserId(owner);
        c.setName(name);
        return categories.save(c).getId();
    }

    private UUID newImport(UUID owner, UUID account) {
        StatementImport s = new StatementImport();
        s.setUserId(owner);
        s.setAccountId(account);
        s.setFileName("sample.csv");
        s.setSourceFormat("CSV");
        s.setFileContent(new byte[]{1});
        s.setContentHash("ai-sort-run-it-" + UUID.randomUUID());
        return statementImports.save(s).getId();
    }
}
