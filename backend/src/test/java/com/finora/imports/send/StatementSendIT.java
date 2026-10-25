package com.finora.imports.send;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.ImportJob;
import com.finora.entity.User;
import com.finora.repository.ImportJobRepository;
import com.finora.repository.RefreshTokenRepository;
import com.finora.repository.UserRepository;
import com.finora.security.JwtService;
import com.finora.testsupport.TestSessions;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gate 2b spec §7: send a statement from your computer. The same properties as ImportJobEndpointIT
 * (storage on, worker off, the import limit raised), so the two share one Spring context. The two
 * send limiters count per address in Redis and every call here comes from loopback, so each test
 * starts with both cleared.
 */
@TestPropertySource(properties = {
        "app.statement-storage.provider=filesystem",
        "app.statement-storage.filesystem.root=${java.io.tmpdir}/finora-it-statement-storage",
        "app.import.queue.enabled=false",
        "app.rate-limit.import-stage.max=10000"
})
class StatementSendIT extends AbstractIntegrationTest {

    private static final String CSV = """
            Date,Description,Amount,Type
            2026-07-10,SWIGGY ORDER,486.00,DEBIT
            2026-07-11,BLINKIT GROCERIES,1240.50,DEBIT
            """;

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private ImportJobRepository jobRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private StringRedisTemplate redis;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void clearSendLimiters() {
        for (String limiter : List.of("send-code-failure", "send-statement")) {
            var keys = redis.keys("ratelimit:" + limiter + ":*");
            if (keys != null && !keys.isEmpty()) redis.delete(keys);
        }
    }

    private User user() {
        User user = new User();
        user.setEmail("statement-send-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Statement Send IT User");
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    private HttpHeaders bearerFor(User user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestSessions.accessTokenFor(jwtService, refreshTokens, user));
        return headers;
    }

    private JsonNode read(ResponseEntity<String> response) {
        try {
            return mapper.readTree(response.getBody());
        } catch (Exception e) {
            throw new AssertionError("not JSON: " + response.getBody(), e);
        }
    }

    private String issue(User user) {
        ResponseEntity<String> response = restTemplate.exchange("/api/v1/imports/send-codes", HttpMethod.POST,
                new HttpEntity<>(bearerFor(user)), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return read(response).get("data").get("code").asText();
    }

    private JsonNode status(User user) {
        ResponseEntity<String> response = restTemplate.exchange("/api/v1/imports/send-codes/current", HttpMethod.GET,
                new HttpEntity<>(bearerFor(user)), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return read(response).get("data");
    }

    /** The public page's call: no Authorization header, no cookie. */
    private ResponseEntity<String> send(String code, String fileName, byte[] content) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("code", code);
        body.add("file", new ByteArrayResource(content) {
            @Override public String getFilename() { return fileName; }
        });
        return restTemplate.exchange("/api/v1/send", HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private ResponseEntity<String> sendCsv(String code) {
        return send(code, "statement.csv", CSV.getBytes(StandardCharsets.UTF_8));
    }

    private List<ImportJob> jobsOf(User user) {
        return jobRepository.findAll().stream().filter(j -> j.getUserId().equals(user.getId())).toList();
    }

    private void expire(User user) {
        jdbc.update("UPDATE statement_send_codes SET expires_at = now() - interval '1 second' WHERE user_id = ?",
                user.getId());
    }

    @Test
    void issueThenUploadCreatesAJobForTheIssuer() {
        User user = user();
        String code = issue(user);

        ResponseEntity<String> response = sendCsv(code);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(jobsOf(user)).hasSize(1);
    }

    @Test
    void statusGoesWaitingThenReceived() {
        User user = user();
        assertThat(status(user).get("state").asText()).isEqualTo("NONE");
        String code = issue(user);
        assertThat(status(user).get("state").asText()).isEqualTo("WAITING");

        sendCsv(code);

        JsonNode after = status(user);
        assertThat(after.get("state").asText()).isEqualTo("RECEIVED");
        assertThat(after.get("importJobId").asText()).isEqualTo(jobsOf(user).get(0).getId().toString());
    }

    /** Ten minutes and one second after it was issued (spec §7: valid 10 minutes). */
    @Test
    void expiredCodeRefused() {
        User user = user();
        String code = issue(user);
        jdbc.update("UPDATE statement_send_codes SET created_at = now() - interval '10 minutes 1 second', "
                + "expires_at = now() - interval '1 second' WHERE user_id = ?", user.getId());

        ResponseEntity<String> response = sendCsv(code);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(read(response).get("errorCode").asText()).isEqualTo("IMPORT_024");
        assertThat(status(user).get("state").asText()).isEqualTo("EXPIRED");
        assertThat(jobsOf(user)).isEmpty();
    }

    @Test
    void usedCodeRefused() {
        User user = user();
        String code = issue(user);
        assertThat(sendCsv(code).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        ResponseEntity<String> again = send(code, "other.csv",
                (CSV + "2026-07-12,ZEPTO,99.00,DEBIT\n").getBytes(StandardCharsets.UTF_8));

        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(jobsOf(user)).hasSize(1);
    }

    @Test
    void newCodeCancelsTheOld() {
        User user = user();
        String first = issue(user);
        String second = issue(user);

        assertThat(sendCsv(first).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(sendCsv(second).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    }

    /** Wrong, expired, used and cancelled codes: one status, one code, one message, so an answer never
     *  tells a guesser which codes exist. */
    @Test
    void failureKindsAreIndistinguishable() {
        User expiredOwner = user();
        String expired = issue(expiredOwner);
        expire(expiredOwner);
        User usedOwner = user();
        String used = issue(usedOwner);
        sendCsv(used);
        User cancelledOwner = user();
        String cancelled = issue(cancelledOwner);
        issue(cancelledOwner);
        String wrong = "ZZZZZZ".equals(used) ? "YYYYYY" : "ZZZZZZ";

        List<ResponseEntity<String>> answers = List.of(sendCsv(wrong), sendCsv(expired), sendCsv(used), sendCsv(cancelled));

        for (ResponseEntity<String> answer : answers) {
            assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(read(answer).get("errorCode").asText()).isEqualTo("IMPORT_024");
            assertThat(read(answer).get("message").asText())
                    .isEqualTo("That code didn't work. Check it on your phone, or ask for a new one.");
        }
    }

    /** Five codes that didn't work from one address block it, even for a right code after them. */
    @Test
    void fiveFailuresThenTooManyRequests() {
        User user = user();
        String code = issue(user);
        for (int i = 0; i < 5; i++) {
            assertThat(sendCsv("WRONG" + i).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        ResponseEntity<String> blocked = sendCsv(code);

        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(read(blocked).get("errorCode").asText()).isEqualTo("IMPORT_025");
        assertThat(jobsOf(user)).isEmpty();
        assertThat(status(user).get("state").asText()).as("the right code was never even tried").isEqualTo("WAITING");
        // Sid (25 Oct): the block lasts 15 minutes, not an hour.
        var keys = redis.keys("ratelimit:send-code-failure:*");
        assertThat(keys).hasSize(1);
        assertThat(redis.getExpire(keys.iterator().next())).isBetween(1L, 900L);
        assertThat(read(blocked).get("message").asText())
                .isEqualTo("Too many codes didn't work from this network. Try again in 15 minutes.");
    }

    @Test
    void lockedPdfRefusedWithPasswordRequired() throws Exception {
        User user = user();
        String code = issue(user);
        byte[] locked = com.finora.imports.pdf.fixtures.PdfFixtureBuilder.encrypt(
                com.finora.imports.pdf.fixtures.PdfFixtureBuilder.buildReverseChronologicalRunningBalanceSample(),
                "AAAA1234");

        ResponseEntity<String> response = send(code, "statement.pdf", locked);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(read(response).get("errorCode").asText()).isEqualTo("IMPORT_008");
        assertThat(jobsOf(user)).isEmpty();
    }

    /** A refused file gives the code back: a person who picked the wrong file can try again. */
    @Test
    void refusedFileReleasesTheCode() {
        User user = user();
        String code = issue(user);

        ResponseEntity<String> empty = send(code, "statement.csv", new byte[0]);
        assertThat(empty.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(read(empty).get("errorCode").asText()).isEqualTo("IMPORT_021");
        assertThat(status(user).get("state").asText()).isEqualTo("WAITING");

        assertThat(sendCsv(code).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    }

    @Test
    void acceptedFileBurnsTheCode() {
        User user = user();
        String code = issue(user);
        sendCsv(code);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT used_at, import_job_id FROM statement_send_codes WHERE user_id = ?", user.getId());
        assertThat(row.get("used_at")).isNotNull();
        assertThat(row.get("import_job_id")).isEqualTo(jobsOf(user).get(0).getId());
    }

    @Test
    void concurrentUploadsWithOneCodeMakeOneJob() throws Exception {
        User user = user();
        String code = issue(user);
        CountDownLatch start = new CountDownLatch(1);
        List<CompletableFuture<ResponseEntity<String>>> uploads = List.of(
                CompletableFuture.supplyAsync(() -> { await(start); return sendCsv(code); }),
                CompletableFuture.supplyAsync(() -> { await(start); return send(code, "b.csv",
                        (CSV + "2026-07-13,UBER,210.00,DEBIT\n").getBytes(StandardCharsets.UTF_8)); }));
        start.countDown();

        List<HttpStatus> statuses = uploads.stream().map(f -> (HttpStatus) f.join().getStatusCode()).toList();

        assertThat(statuses).containsExactlyInAnyOrder(HttpStatus.ACCEPTED, HttpStatus.BAD_REQUEST);
        assertThat(jobsOf(user)).hasSize(1);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void responseCarriesNoAccountData() {
        User user = user();
        String code = issue(user);

        ResponseEntity<String> response = sendCsv(code);

        String body = response.getBody();
        assertThat(read(response).get("data").get("received").asBoolean()).isTrue();
        assertThat(body).doesNotContain(user.getEmail(), user.getFullName(), user.getId().toString(),
                jobsOf(user).get(0).getId().toString());
    }

    @Test
    void onlyHashStored() {
        User user = user();
        String code = issue(user);

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM statement_send_codes WHERE user_id = ?", user.getId());

        assertThat(row.values()).noneMatch(v -> v != null && v.toString().contains(code));
        assertThat(row.get("code_hash")).isEqualTo(StatementSendService.hash(code));
    }

    @Test
    void issueAndUseAreAudited() {
        User user = user();
        sendCsv(issue(user));

        List<String> actions = jdbc.queryForList(
                "SELECT action FROM audit_logs WHERE user_id = ? AND action LIKE 'STATEMENT_SEND_CODE_%' ORDER BY created_at",
                String.class, user.getId());
        assertThat(actions).containsExactly("STATEMENT_SEND_CODE_ISSUED", "STATEMENT_SEND_CODE_USED");
        String usedMetadata = jdbc.queryForObject(
                "SELECT metadata::text FROM audit_logs WHERE user_id = ? AND action = 'STATEMENT_SEND_CODE_USED'",
                String.class, user.getId());
        assertThat(usedMetadata).contains("\"ip\"", jobsOf(user).get(0).getId().toString());
    }

    @Test
    void publicEndpointNeedsNoAuth() {
        User user = user();
        assertThat(sendCsv(issue(user)).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    }

    @Test
    void issueNeedsAuth() {
        ResponseEntity<String> issue = restTemplate.exchange("/api/v1/imports/send-codes", HttpMethod.POST,
                HttpEntity.EMPTY, String.class);
        ResponseEntity<String> current = restTemplate.exchange("/api/v1/imports/send-codes/current", HttpMethod.GET,
                HttpEntity.EMPTY, String.class);

        assertThat(issue.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(current.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ---- Found in review ----

    /** An upload that claimed the code and never finished (the computer dropped mid-upload, or the
     *  server restarted) must not leave the phone on "Waiting for your file…" for good. A claim
     *  older than five minutes with no job is over. */
    @Test
    void aClaimThatNeverFinishedEndsAsExpired() {
        User user = user();
        issue(user);
        jdbc.update("UPDATE statement_send_codes SET used_at = now() - interval '6 minutes' WHERE user_id = ?",
                user.getId());

        assertThat(status(user).get("state").asText()).isEqualTo("EXPIRED");
    }

    /** A claim still uploading is still waiting, even just past the code's 10 minutes (a slow upload
     *  or a waking malware scanner): the phone must not give up on a file that is arriving. */
    @Test
    void aClaimStillUploadingIsWaitingEvenPastExpiry() {
        User user = user();
        issue(user);
        jdbc.update("UPDATE statement_send_codes SET used_at = now() - interval '30 seconds', "
                + "expires_at = now() - interval '5 seconds' WHERE user_id = ?", user.getId());

        assertThat(status(user).get("state").asText()).isEqualTo("WAITING");
    }

    /** The public path checks no session, so it checks the account: a code issued just before the
     *  account was suspended, deactivated or set for deletion adds nothing. */
    @Test
    void aCodeOfAnAccountNoLongerActiveDoesNotWork() {
        for (String status : List.of(User.STATUS_SUSPENDED, User.STATUS_DEACTIVATED, User.STATUS_PENDING_DELETION)) {
            User user = user();
            String code = issue(user);
            jdbc.update("UPDATE users SET status = ? WHERE id = ?", status, user.getId());

            ResponseEntity<String> response = sendCsv(code);

            assertThat(response.getStatusCode()).as(status).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(read(response).get("errorCode").asText()).as(status).isEqualTo("IMPORT_024");
            assertThat(jobsOf(user)).as(status).isEmpty();
            clearSendLimiters();
        }
    }

    @Autowired private StatementSendCodeRetentionSweep retentionSweep;

    /** Codes are dead within minutes; after a day nothing reads them (the audit log is the lasting
     *  record), so the daily sweep deletes them, like email sign-in codes. A newer one stays. */
    @Test
    void codesOlderThanADayAreSweptAndNewerOnesStay() {
        User old = user();
        issue(old);
        jdbc.update("UPDATE statement_send_codes SET created_at = now() - interval '25 hours', "
                + "expires_at = now() - interval '24 hours 50 minutes' WHERE user_id = ?", old.getId());
        User recent = user();
        issue(recent);

        retentionSweep.sweep();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM statement_send_codes WHERE user_id = ?",
                Integer.class, old.getId())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM statement_send_codes WHERE user_id = ?",
                Integer.class, recent.getId())).isEqualTo(1);
        assertThat(status(old).get("state").asText()).isEqualTo("NONE");
    }

    /** Break test: wrong codes sent all at once. The database step is held until every caller has
     *  reached it (or 2 s pass), so all fifteen are inside claim() together. Each takes its slot
     *  before its code is tried, so exactly five reach the database and the rest are refused unread.
     *  Over HTTP this race is real but rarely lines up (a live burst of 15 tried 8 codes before this
     *  fix); here it always does. */
    @Test
    void aBurstOfSimultaneousWrongCodesTriesOnlyFive() throws Exception {
        int callers = 15;
        CountDownLatch inQuery = new CountDownLatch(callers);
        java.util.concurrent.atomic.AtomicInteger queries = new java.util.concurrent.atomic.AtomicInteger();
        JdbcTemplate slowJdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
        org.mockito.Mockito.when(slowJdbc.query(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<Object>>any(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(call -> {
                    queries.incrementAndGet();
                    inQuery.countDown();
                    inQuery.await(2, java.util.concurrent.TimeUnit.SECONDS);
                    return List.of();
                });
        com.finora.config.RequestMetadata metadata = org.mockito.Mockito.mock(com.finora.config.RequestMetadata.class);
        org.mockito.Mockito.when(metadata.ip()).thenReturn("burst-" + UUID.randomUUID());
        StatementSendService service = new StatementSendService(slowJdbc,
                org.mockito.Mockito.mock(com.finora.imports.jobs.ImportJobService.class),
                org.mockito.Mockito.mock(com.finora.service.AuditService.class), metadata, redis, 5, 900);

        ExecutorService pool = Executors.newFixedThreadPool(callers);
        List<java.util.concurrent.Future<String>> answers = new java.util.ArrayList<>();
        try {
            CountDownLatch start = new CountDownLatch(1);
            for (int i = 0; i < callers; i++) {
                answers.add(pool.submit(() -> {
                    start.await();
                    try {
                        service.claim("WRNGZZ");
                        return "CLAIMED";
                    } catch (com.finora.exception.ApiException e) {
                        return e.getCode().code();
                    }
                }));
            }
            start.countDown();
            List<String> codes = new java.util.ArrayList<>();
            for (var answer : answers) codes.add(answer.get(10, java.util.concurrent.TimeUnit.SECONDS));

            assertThat(queries.get()).as("codes actually tried").isEqualTo(5);
            assertThat(codes).filteredOn("IMPORT_024"::equals).hasSize(5);
            assertThat(codes).filteredOn("IMPORT_025"::equals).hasSize(10);
        } finally {
            pool.shutdownNow();
        }
    }

    /** A code that works gives its slot back: four wrong, one right, then one more wrong is still
     *  tried, and only the sixth attempt is blocked. */
    @Test
    void aRightCodeDoesNotCountAgainstTheAddress() {
        User user = user();
        String code = issue(user);
        for (int i = 0; i < 4; i++) {
            assertThat(sendCsv("WRONG" + i).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
        assertThat(sendCsv(code).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        assertThat(sendCsv("WRONG4").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(sendCsv("WRONG5").getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    /** Break test: one user asking for codes many times at once (a double tap, a sheet closed and
     *  reopened mid-request). Requests run one at a time per user, so exactly one code is left live,
     *  and the status names it. 25 users, 8 requests each, all released together, so that without
     *  the lock two requests' cancel-then-insert steps reliably interleave somewhere. */
    @Test
    void simultaneousCodeRequestsLeaveOneLiveCode() throws Exception {
        List<User> users = new java.util.ArrayList<>();
        for (int i = 0; i < 25; i++) users.add(user());
        ExecutorService pool = Executors.newFixedThreadPool(32);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<CompletableFuture<ResponseEntity<String>>> requests = new java.util.ArrayList<>();
            for (User user : users) {
                HttpHeaders headers = bearerFor(user);
                for (int i = 0; i < 8; i++) {
                    requests.add(CompletableFuture.supplyAsync(() -> {
                        await(start);
                        return restTemplate.exchange("/api/v1/imports/send-codes", HttpMethod.POST,
                                new HttpEntity<>(headers), String.class);
                    }, pool));
                }
            }
            start.countDown();
            requests.forEach(r -> assertThat(r.join().getStatusCode()).isEqualTo(HttpStatus.OK));
        } finally {
            pool.shutdownNow();
        }

        for (User user : users) {
            List<UUID> live = jdbc.queryForList("SELECT id FROM statement_send_codes WHERE user_id = ? "
                    + "AND used_at IS NULL AND cancelled_at IS NULL AND expires_at > now()", UUID.class, user.getId());
            assertThat(live).as("live codes of one user").hasSize(1);
            // Codes are stamped in the order they were issued: every code the live one replaced is
            // strictly older. With now() (the transaction's start, before the lock) two requests
            // could tie or invert, and the status named a cancelled code.
            Integer notOlder = jdbc.queryForObject("SELECT count(*) FROM statement_send_codes c "
                    + "WHERE c.user_id = ? AND c.id <> ? AND c.created_at >= "
                    + "(SELECT created_at FROM statement_send_codes WHERE id = ?)",
                    Integer.class, user.getId(), live.get(0), live.get(0));
            assertThat(notOlder).as("codes stamped at or after the live one").isZero();
            assertThat(status(user).get("codeId").asText()).isEqualTo(live.get(0).toString());
        }
    }

    /** The phone compares ids: the status names the code the phone was given. */
    @Test
    void statusNamesTheIssuedCode() {
        User user = user();
        ResponseEntity<String> issued = restTemplate.exchange("/api/v1/imports/send-codes", HttpMethod.POST,
                new HttpEntity<>(bearerFor(user)), String.class);
        String id = read(issued).get("data").get("id").asText();

        assertThat(status(user).get("codeId").asText()).isEqualTo(id);
        issue(user);
        assertThat(status(user).get("codeId").asText()).as("a newer code replaced it").isNotEqualTo(id);
    }

    /** Break test: a script asking for codes in a loop. Ten in 10 minutes, then refused. */
    @Test
    void codeRequestsAreCappedPerUser() {
        User user = user();
        for (int i = 0; i < StatementSendService.MAX_CODES_PER_WINDOW; i++) {
            issue(user);
        }

        ResponseEntity<String> refused = restTemplate.exchange("/api/v1/imports/send-codes", HttpMethod.POST,
                new HttpEntity<>(bearerFor(user)), String.class);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM statement_send_codes WHERE user_id = ?",
                Integer.class, user.getId())).isEqualTo(StatementSendService.MAX_CODES_PER_WINDOW);
        User other = user();
        issue(other);
    }
}
