package com.finora.activation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
import com.finora.entity.Account;
import com.finora.entity.User;
import com.finora.repository.AccountRepository;
import com.finora.repository.UserRepository;
import com.finora.testsupport.FakePhoneVerificationProvider;
import com.finora.testsupport.TestPhoneVerificationConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;

/**
 * Gate 2a spec §5: the optional reason someone gives when deleting their account, kept with the month
 * only and no link to them. Drives the real flow over HTTP -- register, verify the phone, start and
 * verify a password-change session, then delete -- so the reason is proven to be written only by a
 * deletion that actually happened.
 *
 * <p>The table holds no user id, so a test finds its own row by month: each test pins "today" to a
 * month no other test uses. Client IPs come from 192.0.2.0/24, which no other test uses, so the
 * shared per-IP rate limits never see this class's requests together with another's.
 */
@Import(TestPhoneVerificationConfig.class)
class AccountDeletionReasonIT extends AbstractIntegrationTest {

    @DynamicPropertySource
    static void enableTrustProxyHeaders(DynamicPropertyRegistry registry) {
        registry.add("app.security.trust-proxy-headers", () -> "true");
    }

    // A plain placeholder (the only rule is 8-72 characters), so the secret scanner never mistakes it for a key.
    private static final String PASSWORD = "testtesttest";
    private static final AtomicInteger CLIENT_IP = new AtomicInteger(101);

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private JdbcTemplate jdbc;
    @MockitoSpyBean private ReportingDay reportingDay;
    private final ObjectMapper mapper = new ObjectMapper();

    private record Signed(UUID userId, String token, String phone, String ip) {}

    @Test
    void reasonStoredWithMonthAndNoLink() throws Exception {
        LocalDate month = pinMonth(2031, 1);
        Signed user = signUp();

        assertThat(delete(user, verifiedSession(user), "\"NOTHING_USEFUL\"").getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(rowsFor(month)).singleElement().satisfies(row -> {
            assertThat(row.reason()).isEqualTo("NOTHING_USEFUL");
            assertThat(row.hadData()).isFalse();
        });
        assertThat(jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_name = 'account_deletion_reasons' "
                        + "ORDER BY column_name", String.class))
                .as("no user id, no timestamp, nothing finer than the month")
                .containsExactly("deletion_month", "had_data", "id", "reason");
    }

    @Test
    void hadDataTrueWhenAStatementWasImported() throws Exception {
        LocalDate month = pinMonth(2031, 2);
        Signed user = signUp();
        statementImported(user.userId());

        assertThat(delete(user, verifiedSession(user), "\"COULDNT_GET_STATEMENTS_IN\"").getStatusCode())
                .isEqualTo(HttpStatus.OK);

        assertThat(rowsFor(month)).singleElement().satisfies(row -> assertThat(row.hadData()).isTrue());
    }

    @Test
    void noReasonWritesNothing() throws Exception {
        LocalDate month = pinMonth(2031, 3);
        Signed user = signUp();

        assertThat(delete(user, verifiedSession(user), null).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(userRepository.findById(user.userId())).get().satisfies(u -> assertThat(u.isDeleted()).isTrue());
        assertThat(rowsFor(month)).isEmpty();
    }

    /** A reason chosen, then the deletion refused (an expired or invented session): nothing is kept. */
    @Test
    void reasonNotStoredWhenDeletionRefused() throws Exception {
        LocalDate month = pinMonth(2031, 4);
        Signed user = signUp();

        assertThat(delete(user, UUID.randomUUID().toString(), "\"USE_ANOTHER_APP\"").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        assertThat(userRepository.findById(user.userId())).get().satisfies(u -> assertThat(u.isDeleted()).isFalse());
        assertThat(rowsFor(month)).isEmpty();
    }

    @Test
    void unknownReasonIsRefused() throws Exception {
        LocalDate month = pinMonth(2031, 5);
        Signed user = signUp();

        assertThat(delete(user, verifiedSession(user), "\"NOPE\"").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        assertThat(userRepository.findById(user.userId())).get().satisfies(u -> assertThat(u.isDeleted()).isFalse());
        assertThat(rowsFor(month)).isEmpty();
    }

    /** The purge runs inside the delete call and wipes everything linked to the user; the reason is not. */
    @Test
    void reasonSurvivesPurge() throws Exception {
        LocalDate month = pinMonth(2031, 6);
        Signed user = signUp();
        statementImported(user.userId());

        assertThat(delete(user, verifiedSession(user), "\"DONT_NEED_IT\"").getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(userRepository.findById(user.userId())).get().satisfies(u -> assertThat(u.isDeleted()).isTrue());
        // The purge soft-deletes statements (never a hard delete: see AccountPurgeSweepService).
        assertThat(jdbc.queryForObject("SELECT count(*) FROM statement_imports WHERE user_id = ? AND deleted_at IS NULL",
                Long.class, user.userId())).as("the purge ran").isZero();
        assertThat(rowsFor(month)).singleElement().satisfies(row -> assertThat(row.reason()).isEqualTo("DONT_NEED_IT"));
    }

    /**
     * The same delete sent twice at once (a double tap past the client's guard, or a retry): the
     * session's version check lets one through, and the other rolls back with its reason row, so a
     * deletion is counted once.
     */
    @Test
    void concurrentDeletesKeepOneReason() throws Exception {
        LocalDate month = pinMonth(2031, 9);
        Signed user = signUp();
        String sessionId = verifiedSession(user);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Future<Integer> a = pool.submit(() -> {
                start.await();
                return delete(user, sessionId, "\"NOTHING_USEFUL\"").getStatusCode().value();
            });
            java.util.concurrent.Future<Integer> b = pool.submit(() -> {
                start.await();
                return delete(user, sessionId, "\"NOTHING_USEFUL\"").getStatusCode().value();
            });
            start.countDown();
            List<Integer> statuses = List.of(a.get(), b.get());
            assertThat(statuses).as("exactly one deletion succeeds").containsOnlyOnce(200);
        } finally {
            pool.shutdownNow();
        }
        assertThat(rowsFor(month)).hasSize(1);
    }

    /** The enum and V278's CHECK are two copies of one list; this fails the moment they drift. */
    @Test
    void everyReasonIsAcceptedByTheDatabase() {
        LocalDate month = LocalDate.of(2031, 7, 1);
        for (DeletionReason reason : DeletionReason.values()) {
            jdbc.update("INSERT INTO account_deletion_reasons (reason, deletion_month, had_data) VALUES (?, ?, false)",
                    reason.name(), month);
        }
        assertThat(rowsFor(month)).hasSize(DeletionReason.values().length);
    }

    @Test
    void theDatabaseKeepsTheMonthOnly() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO account_deletion_reasons (reason, deletion_month, had_data) VALUES ('DONT_NEED_IT', ?, false)",
                        LocalDate.of(2031, 8, 17)))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    // ---- helpers -----------------------------------------------------------------------------

    private record Row(String reason, boolean hadData) {}

    /** Pins "today" to the 17th of that month and returns the month as stored (its first day). */
    private LocalDate pinMonth(int year, int month) {
        doReturn(LocalDate.of(year, month, 17)).when(reportingDay).today();
        return LocalDate.of(year, month, 1);
    }

    private List<Row> rowsFor(LocalDate month) {
        return jdbc.query("SELECT reason, had_data FROM account_deletion_reasons WHERE deletion_month = ?",
                (rs, i) -> new Row(rs.getString("reason"), rs.getBoolean("had_data")), month);
    }

    private Signed signUp() throws Exception {
        String email = "deletion-reason-" + UUID.randomUUID() + "@example.com";
        // 10 digits after +91, matching AuthDtos.PHONE_REGEXP.
        String phone = "+9197654" + (10000 + new java.util.Random().nextInt(90000));
        String ip = "192.0.2." + CLIENT_IP.getAndIncrement();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Forwarded-For", ip);
        ResponseEntity<String> registered = restTemplate.postForEntity("/api/v1/auth/register", new HttpEntity<>("""
                {"email": "%s", "password": "%s", "fullName": "Deletion Reason Test", "phoneNumber": "%s"}
                """.formatted(email, PASSWORD, phone), headers), String.class);
        assertThat(registered.getStatusCode()).isEqualTo(HttpStatus.OK);
        String token = mapper.readTree(registered.getBody()).get("data").get("token").asText();

        ResponseEntity<String> verified = restTemplate.postForEntity("/api/v1/phone/verify", new HttpEntity<>("""
                {"firebaseIdToken": "%s"}
                """.formatted(FakePhoneVerificationProvider.tokenFor(phone)), auth(token, ip)), String.class);
        assertThat(verified.getStatusCode()).isEqualTo(HttpStatus.OK);

        UUID userId = userRepository.findByEmailIgnoreCaseAndAccountScope(email, User.SCOPE_USER).orElseThrow().getId();
        return new Signed(userId, token, phone, ip);
    }

    /** A password-change session at OTP_VERIFIED: the proof the delete endpoint requires. */
    private String verifiedSession(Signed user) throws Exception {
        ResponseEntity<String> started = restTemplate.postForEntity("/api/v1/users/me/password-change/start",
                new HttpEntity<>("{\"currentPassword\": \"%s\"}".formatted(PASSWORD), auth(user.token(), user.ip())),
                String.class);
        assertThat(started.getStatusCode()).isEqualTo(HttpStatus.OK);
        String sessionId = mapper.readTree(started.getBody()).get("data").get("sessionId").asText();

        ResponseEntity<String> otp = restTemplate.postForEntity("/api/v1/users/me/password-change/verify-otp",
                new HttpEntity<>("{\"sessionId\": \"%s\", \"firebaseIdToken\": \"%s\"}".formatted(
                        sessionId, FakePhoneVerificationProvider.tokenFor(user.phone())), auth(user.token(), user.ip())),
                String.class);
        assertThat(otp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return sessionId;
    }

    /** @param reasonJson a JSON value for {@code reason}, or null to leave the field out. */
    private ResponseEntity<String> delete(Signed user, String sessionId, String reasonJson) {
        String body = reasonJson == null
                ? "{\"sessionId\": \"%s\"}".formatted(sessionId)
                : "{\"sessionId\": \"%s\", \"reason\": %s}".formatted(sessionId, reasonJson);
        return restTemplate.postForEntity("/api/v1/users/me/account/delete",
                new HttpEntity<>(body, auth(user.token(), user.ip())), String.class);
    }

    private HttpHeaders auth(String token, String ip) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        headers.set("X-Forwarded-For", ip);
        return headers;
    }

    private void statementImported(UUID userId) {
        Account account = new Account();
        account.setUserId(userId);
        account.setName("Deletion Reason IT Savings");
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(BigDecimal.ZERO);
        UUID accountId = accountRepository.save(account).getId();
        jdbc.update("INSERT INTO statement_imports (user_id, account_id, file_name, file_content) VALUES (?, ?, ?, ?)",
                userId, accountId, "statement.csv", "Date,Description,Amount\n".getBytes(StandardCharsets.UTF_8));
    }
}
