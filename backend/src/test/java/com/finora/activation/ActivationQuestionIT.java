package com.finora.activation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
import com.finora.entity.Account;
import com.finora.entity.User;
import com.finora.imports.ImportSessionService;
import com.finora.imports.jobs.ImportJobWorker;
import com.finora.repository.AccountRepository;
import com.finora.repository.RefreshTokenRepository;
import com.finora.repository.UserRepository;
import com.finora.security.JwtService;
import com.finora.testsupport.TestSessions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;

/**
 * Gate 2a spec §4: which "What stopped you?" question the dashboard shows, and the limits on
 * answering. "Today" is pinned through the spied {@link ReportingDay}, so day arithmetic does not
 * depend on when the test runs.
 */
@TestPropertySource(properties = {
        "app.statement-storage.provider=filesystem",
        "app.statement-storage.filesystem.root=${java.io.tmpdir}/finora-activation-question-it",
        "app.import.queue.enabled=false"
})
class ActivationQuestionIT extends AbstractIntegrationTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 20);
    private static final Instant NOW = Instant.parse("2026-11-20T06:00:00Z");

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ObjectMapper mapper;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ImportFunnelDayRepository funnelDays;
    @Autowired private ActivationAnswerRepository answers;
    @Autowired private ActivationFacts facts;
    @Autowired private ImportJobWorker worker;
    @Autowired private ImportSessionService importSessionService;
    @MockitoSpyBean private ReportingDay reportingDay;

    @BeforeEach
    void pinToday() {
        pinTo(TODAY, NOW);
    }

    private void pinTo(LocalDate today, Instant now) {
        doReturn(today).when(reportingDay).today();
        doReturn(now).when(reportingDay).now();
    }

    // ---- the dashboard question -------------------------------------------------------------

    @Test
    void noFileAskedTheDayAfterOpeningImport() throws Exception {
        User user = user();
        funnelDays.record(user.getId(), "IMPORT_SCREEN_OPENED", TODAY.minusDays(1));

        assertThat(question(user)).isEqualTo("NO_FILE");
    }

    @Test
    void noFileNotAskedOnTheSameDay() throws Exception {
        User user = user();
        funnelDays.record(user.getId(), "IMPORT_SCREEN_OPENED", TODAY);

        assertThat(question(user)).isNull();
    }

    @Test
    void noFileNotAskedAfterAFileWasChosen() throws Exception {
        User user = user();
        funnelDays.record(user.getId(), "IMPORT_SCREEN_OPENED", TODAY.minusDays(3));
        funnelDays.record(user.getId(), "FILE_CHOSEN", TODAY.minusDays(3));

        assertThat(question(user)).isNull();
    }

    @Test
    void noFileNotAskedOnceDataIsIn() throws Exception {
        User user = user();
        funnelDays.record(user.getId(), "IMPORT_SCREEN_OPENED", TODAY.minusDays(3));
        statementImported(user, true);

        assertThat(question(user)).as("a deleted statement still means data got in").isNull();
    }

    @Test
    void neverOpenedImportIsNotAsked() throws Exception {
        assertThat(question(user())).isNull();
    }

    @Test
    void notConfirmedAskedForAnExpiryWithinSevenDays() throws Exception {
        User user = user();
        readExpiredUnconfirmed(user, NOW.minus(7, ChronoUnit.DAYS).plusSeconds(60));

        assertThat(question(user)).isEqualTo("NOT_CONFIRMED");
    }

    @Test
    void notConfirmedNotAskedForAnExpiryEightDaysOld() throws Exception {
        User user = user();
        readExpiredUnconfirmed(user, NOW.minus(8, ChronoUnit.DAYS));

        assertThat(question(user)).isNull();
    }

    @Test
    void notConfirmedAskedEvenWithDataIn() throws Exception {
        User user = user();
        statementImported(user, false);
        readExpiredUnconfirmed(user, NOW.minus(1, ChronoUnit.DAYS));

        assertThat(question(user)).isEqualTo("NOT_CONFIRMED");
    }

    /** Someone whose read expired and who then got a statement in is not asked why they stopped. */
    @Test
    void notConfirmedNotAskedOnceAStatementWasImportedAfterTheExpiry() throws Exception {
        User user = user();
        readExpiredUnconfirmed(user, NOW.minus(2, ChronoUnit.DAYS));
        statementImportedAt(user, NOW.minus(1, ChronoUnit.DAYS));

        assertThat(question(user)).isNull();
    }

    @Test
    void notConfirmedStillAskedWhenTheOnlyImportCameBeforeTheExpiry() throws Exception {
        User user = user();
        statementImportedAt(user, NOW.minus(3, ChronoUnit.DAYS));
        readExpiredUnconfirmed(user, NOW.minus(2, ChronoUnit.DAYS));

        assertThat(question(user)).isEqualTo("NOT_CONFIRMED");
    }

    @Test
    void notConfirmedComesBeforeNoFile() throws Exception {
        User user = user();
        funnelDays.record(user.getId(), "IMPORT_SCREEN_OPENED", TODAY.minusDays(2));
        readExpiredUnconfirmed(user, NOW.minus(1, ChronoUnit.DAYS));

        assertThat(question(user)).isEqualTo("NOT_CONFIRMED");
    }

    @Test
    void noQuestionAfterAnyAnswerToday() throws Exception {
        User user = user();
        funnelDays.record(user.getId(), "IMPORT_SCREEN_OPENED", TODAY.minusDays(1));
        assertThat(answer(user, "REFUSED", "TRY_ANOTHER_FILE").getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(question(user)).as("one answer or skip a day, of any moment").isNull();
    }

    @Test
    void answeredMomentNotAskedForThirtyDays() throws Exception {
        User user = user();
        funnelDays.record(user.getId(), "IMPORT_SCREEN_OPENED", TODAY.minusDays(1));
        assertThat(answer(user, "NO_FILE", "NOT_NOW").getStatusCode()).isEqualTo(HttpStatus.OK);

        pinTo(TODAY.plusDays(29), NOW.plus(29, ChronoUnit.DAYS));
        assertThat(question(user)).isNull();
        pinTo(TODAY.plusDays(31), NOW.plus(31, ChronoUnit.DAYS));
        assertThat(question(user)).isEqualTo("NO_FILE");
    }

    // ---- an import-screen question (the client knows the moment, the server the limits) ------

    @Test
    void importScreenMomentAskableWhenNothingBlocksIt() throws Exception {
        User user = user();

        assertThat(question(user, "REFUSED")).isEqualTo("REFUSED");
        assertThat(question(user, "NOT_CONFIRMED")).isEqualTo("NOT_CONFIRMED");
    }

    @Test
    void importScreenMomentNotAskableAfterAnyAnswerToday() throws Exception {
        User user = user();
        assertThat(answer(user, "NO_FILE", "SKIPPED").getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(question(user, "REFUSED")).as("one answer or skip a day, of any moment").isNull();
    }

    /** Askable exactly when an answer would be accepted: blocked on day 29, back on day 30. */
    @Test
    void importScreenMomentNotAskableWithinThirtyDaysOfItsLastAnswer() throws Exception {
        User user = user();
        assertThat(answer(user, "REFUSED", "LEAVE_FOR_NOW").getStatusCode()).isEqualTo(HttpStatus.OK);

        pinTo(TODAY.plusDays(29), NOW.plus(29, ChronoUnit.DAYS));
        assertThat(question(user, "REFUSED")).isNull();
        assertThat(answer(user, "REFUSED", "TRY_ANOTHER_FILE").getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(question(user, "NOT_CONFIRMED")).as("another moment is not blocked").isEqualTo("NOT_CONFIRMED");

        pinTo(TODAY.plusDays(30), NOW.plus(30, ChronoUnit.DAYS));
        assertThat(question(user, "REFUSED")).isEqualTo("REFUSED");
        assertThat(answer(user, "REFUSED", "TRY_ANOTHER_FILE").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void unknownMomentNameIsRefused() {
        ResponseEntity<String> response = restTemplate.exchange("/api/v1/activation/question?moment=NOPE", HttpMethod.GET,
                new HttpEntity<>(bearerFor(user())), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ---- answering ---------------------------------------------------------------------------

    @Test
    void anAnswerIsStoredWithTodaysDate() throws Exception {
        User user = user();

        assertThat(answer(user, "NOT_CONFIRMED", "ROWS_LOOKED_WRONG").getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(answers.findByUserIdOrderByAnswerDateAsc(user.getId())).singleElement().satisfies(a -> {
            assertThat(a.getMoment()).isEqualTo(ActivationMoment.NOT_CONFIRMED);
            assertThat(a.getAnswer()).isEqualTo(ActivationAnswer.ROWS_LOOKED_WRONG);
            assertThat(a.getAnswerDate()).isEqualTo(TODAY);
        });
    }

    @Test
    void secondAnswerSameDayIsRefused() throws Exception {
        User user = user();
        assertThat(answer(user, "REFUSED", "TRY_ANOTHER_FILE").getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(answer(user, "NOT_CONFIRMED", "WRONG_FILE").getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(answers.findByUserIdOrderByAnswerDateAsc(user.getId())).hasSize(1);
    }

    /** Two answers sent at the same moment (two devices): the database keeps one, the other is a 409. */
    @Test
    void concurrentAnswersOnTheSameDayKeepOne() throws Exception {
        User user = user();
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Future<HttpStatus> a = pool.submit(() -> {
                start.await();
                return HttpStatus.valueOf(answer(user, "REFUSED", "TRY_ANOTHER_FILE").getStatusCode().value());
            });
            java.util.concurrent.Future<HttpStatus> b = pool.submit(() -> {
                start.await();
                return HttpStatus.valueOf(answer(user, "NOT_CONFIRMED", "WRONG_FILE").getStatusCode().value());
            });
            start.countDown();
            assertThat(java.util.List.of(a.get(), b.get())).containsExactlyInAnyOrder(HttpStatus.OK, HttpStatus.CONFLICT);
        } finally {
            pool.shutdownNow();
        }
        assertThat(answers.findByUserIdOrderByAnswerDateAsc(user.getId())).hasSize(1);
    }

    @Test
    void theDatabaseRefusesASecondAnswerOnTheSameDay() {
        UUID userId = user().getId();
        jdbc.update("INSERT INTO activation_answers (user_id, moment, answer, answer_date) VALUES (?, 'NO_FILE', 'NOT_NOW', ?)",
                userId, TODAY);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO activation_answers (user_id, moment, answer, answer_date) VALUES (?, 'REFUSED', 'SKIPPED', ?)",
                userId, TODAY)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void sameMomentWithinThirtyDaysIsRefused() throws Exception {
        User user = user();
        assertThat(answer(user, "REFUSED", "LEAVE_FOR_NOW").getStatusCode()).isEqualTo(HttpStatus.OK);

        pinTo(TODAY.plusDays(10), NOW.plus(10, ChronoUnit.DAYS));
        assertThat(answer(user, "REFUSED", "TRY_ANOTHER_FILE").getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(answer(user, "NOT_CONFIRMED", "WRONG_FILE").getStatusCode())
                .as("another moment is not blocked").isEqualTo(HttpStatus.OK);
    }

    @Test
    void answerFromAnotherMomentIsRefused() throws Exception {
        User user = user();

        assertThat(answer(user, "REFUSED", "NO_STATEMENT").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(answers.findByUserIdOrderByAnswerDateAsc(user.getId())).isEmpty();
    }

    @Test
    void skipCountsAsAnAnswer() throws Exception {
        User user = user();
        funnelDays.record(user.getId(), "IMPORT_SCREEN_OPENED", TODAY.minusDays(1));

        assertThat(answer(user, "NO_FILE", "SKIPPED").getStatusCode()).isEqualTo(HttpStatus.OK);

        pinTo(TODAY.plusDays(1), NOW.plus(1, ChronoUnit.DAYS));
        assertThat(question(user)).isNull();
    }

    @Test
    void unknownAnswerNameIsRefused() throws Exception {
        User user = user();

        assertThat(postAnswer(user, "{\"moment\":\"NO_FILE\",\"answer\":\"BORED\"}").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(postAnswer(user, "{\"moment\":\"LATER\",\"answer\":\"SKIPPED\"}").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(postAnswer(user, "{\"answer\":\"SKIPPED\"}").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(answers.findByUserIdOrderByAnswerDateAsc(user.getId())).isEmpty();
    }

    /** V276 keeps its own copy of the lists; every enum value must be accepted by it. */
    @Test
    void everyAnswerIsAcceptedByTheDatabase() {
        UUID userId = user().getId();
        int day = 0;
        for (ActivationAnswer answer : ActivationAnswer.values()) {
            ActivationMoment moment = java.util.Arrays.stream(ActivationMoment.values())
                    .filter(answer::allowedFor).findFirst().orElseThrow();
            // One per day: V276 allows a user one answer a day.
            jdbc.update("INSERT INTO activation_answers (user_id, moment, answer, answer_date) VALUES (?, ?, ?, ?)",
                    userId, moment.name(), answer.name(), TODAY.minusDays(day++));
        }
        assertThat(answers.findByUserIdOrderByAnswerDateAsc(userId)).hasSize(ActivationAnswer.values().length);
    }

    @Test
    void questionAndAnswerNeedSignIn() {
        assertThat(restTemplate.getForEntity("/api/v1/activation/question", String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ---- the queued path ---------------------------------------------------------------------

    /**
     * The NOT_CONFIRMED card relies on {@code expired_unconfirmed_at}. A queued import stages through
     * the same ImportService methods that record the read with its session id, so the sweep should
     * stamp it too; this proves it end to end rather than leaving it as a reading of the code.
     */
    @Test
    void expiryStampedForAQueuedImport() throws Exception {
        User user = user();
        Instant before = Instant.now();
        UUID jobId = queuedUpload(user);
        jdbc.update("UPDATE import_sessions SET expires_at = now() - interval '1 minute' WHERE user_id = ?", user.getId());

        importSessionService.sweepExpiredSessions();

        assertThat(jobId).isNotNull();
        assertThat(facts.hasUnconfirmedExpirySince(user.getId(), before.minusSeconds(1))).isTrue();
    }

    // ---- helpers -----------------------------------------------------------------------------

    private User user() {
        User user = new User();
        user.setEmail("activation-question-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Activation Question IT");
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    private HttpHeaders bearerFor(User user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestSessions.accessTokenFor(jwtService, refreshTokens, user));
        return headers;
    }

    /** The dashboard question's moment, or null for none. */
    private String question(User user) throws Exception {
        ResponseEntity<String> response = restTemplate.exchange("/api/v1/activation/question", HttpMethod.GET,
                new HttpEntity<>(bearerFor(user)), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode moment = mapper.readTree(response.getBody()).get("data").get("moment");
        return moment == null || moment.isNull() ? null : moment.asText();
    }

    /** {@code moment} if the limits allow asking it now, else null. */
    private String question(User user, String moment) throws Exception {
        ResponseEntity<String> response = restTemplate.exchange("/api/v1/activation/question?moment=" + moment,
                HttpMethod.GET, new HttpEntity<>(bearerFor(user)), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode asked = mapper.readTree(response.getBody()).get("data").get("moment");
        return asked == null || asked.isNull() ? null : asked.asText();
    }

    private ResponseEntity<String> answer(User user, String moment, String answer) {
        return postAnswer(user, "{\"moment\":\"" + moment + "\",\"answer\":\"" + answer + "\"}");
    }

    private ResponseEntity<String> postAnswer(User user, String json) {
        HttpHeaders headers = bearerFor(user);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange("/api/v1/activation/answers", HttpMethod.POST, new HttpEntity<>(json, headers),
                String.class);
    }

    private void statementImported(User user, boolean deleted) {
        Account account = new Account();
        account.setUserId(user.getId());
        account.setName("Activation IT Savings");
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(BigDecimal.ZERO);
        UUID accountId = accountRepository.save(account).getId();
        jdbc.update("INSERT INTO statement_imports (user_id, account_id, file_name, file_content, deleted_at) "
                        + "VALUES (?, ?, ?, ?, ?)",
                user.getId(), accountId, "statement.csv", JULY.getBytes(StandardCharsets.UTF_8),
                deleted ? Timestamp.from(NOW) : null);
    }

    private void statementImportedAt(User user, Instant importedAt) {
        statementImported(user, false);
        jdbc.update("UPDATE statement_imports SET imported_at = ? WHERE user_id = ?", Timestamp.from(importedAt), user.getId());
    }

    private void readExpiredUnconfirmed(User user, Instant expiredAt) {
        jdbc.update("INSERT INTO statement_analysis_sessions (id, user_id, reference, source, outcome, expired_unconfirmed_at) "
                        + "VALUES (gen_random_uuid(), ?, ?, 'CUSTOMER_IMPORT', 'PARSED', ?)",
                user.getId(), "AQ-" + UUID.randomUUID().toString().substring(0, 18), Timestamp.from(expiredAt));
    }

    /** Synthetic: three ordinary rows, nothing the trust check holds. */
    private static final String JULY = """
            Date,Description,Amount,Type
            2026-07-10,SWIGGY ORDER,486.00,DEBIT
            2026-07-11,BLINKIT GROCERIES,1240.50,DEBIT
            2026-07-14,SALARY CREDIT TEST EMPLOYER,52000.00,CREDIT
            """;

    private UUID queuedUpload(User user) throws Exception {
        HttpHeaders headers = bearerFor(user);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(JULY.getBytes(StandardCharsets.UTF_8)) {
            @Override public String getFilename() { return "statement.csv"; }
        });
        ResponseEntity<String> accepted = restTemplate.exchange("/api/v1/import/jobs", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
        assertThat(accepted.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        worker.drainOnce();
        return UUID.fromString(mapper.readTree(accepted.getBody()).get("data").get("jobId").asText());
    }
}
