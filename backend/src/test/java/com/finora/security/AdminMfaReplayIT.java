package com.finora.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
import com.finora.entity.AdminTotpCredential;
import com.finora.entity.User;
import com.finora.repository.AdminTotpCredentialRepository;
import com.finora.repository.UserRepository;
import com.finora.security.mfa.TotpGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A TOTP code is accepted once (RFC 6238 section 5.2), proven against a real Postgres and the real
 * HTTP stack. The unit tests show the service refuses a used step; what only a real database can
 * show is that the recording of it is atomic (the conditional UPDATE), that the read-only column
 * mapping really lets that UPDATE through and really stops a stale entity overwriting it, and that
 * the whole thing holds through the actual login endpoints.
 *
 * <p>Same {@code @TestPropertySource} as {@link AdminMfaEnforcementIT} so both share one cached
 * Spring context instead of booting a second.
 */
@TestPropertySource(properties = {"app.admin-mfa.enabled=true", "app.admin-mfa.enforced=true"})
class AdminMfaReplayIT extends AbstractIntegrationTest {

    private static final String PASSWORD = "Replay-Test-Pass-77-Fixture";

    /** A step comfortably later than any the enrolment or a sign-in in these tests can record
     *  (real steps are epoch seconds / 30, about 59 million), so a direct claim is never refused
     *  for being old. */
    private static final java.util.function.LongSupplier FUTURE_STEP =
            () -> TotpGenerator.stepAt(Instant.now()) + 100_000L;

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private AdminTotpCredentialRepository credentials;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private PlatformTransactionManager transactionManager;

    private final ObjectMapper mapper = new ObjectMapper();

    /** An admin who has finished enrolment, with the secret their "authenticator app" holds. */
    private record Admin(UUID id, String email, String secret) {}

    private HttpHeaders json() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private ResponseEntity<String> post(HttpHeaders headers, String path, String body) {
        return restTemplate.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private String loginBody(String email) {
        return "{\"identifier\":\"" + email + "\",\"password\":\"" + PASSWORD + "\",\"scope\":\"ADMIN\"}";
    }

    /** Creates an admin, signs in (allowed while unenrolled), and enrols using the code for
     *  {@code enrolCodeAt}. */
    private Admin enrolledAdmin(Instant enrolCodeAt) throws Exception {
        User user = new User();
        user.setEmail("mfa-replay-it-" + UUID.randomUUID() + "@example.test");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setFullName("MFA Replay Fixture");
        user.setRole("ADMIN");
        user.setAccountScope(User.SCOPE_ADMIN);
        user.setPhoneVerified(true);
        user = userRepository.save(user);

        ResponseEntity<String> login = post(json(), "/api/v1/auth/login", loginBody(user.getEmail()));
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        HttpHeaders session = json();
        session.setBearerAuth(mapper.readTree(login.getBody()).at("/data/token").asText());

        String secret = mapper.readTree(post(session, "/api/v1/admin-mfa/enroll", null).getBody())
                .at("/data/secret").asText();
        ResponseEntity<String> confirm = post(session, "/api/v1/admin-mfa/confirm",
                "{\"code\":\"" + TotpGenerator.codeAt(secret, enrolCodeAt) + "\"}");
        assertThat(confirm.getStatusCode()).isEqualTo(HttpStatus.OK);
        return new Admin(user.getId(), user.getEmail(), secret);
    }

    /** A fresh password sign-in for an enrolled admin, which stops at the code prompt. */
    private String challengeFor(Admin admin) throws Exception {
        ResponseEntity<String> login = post(json(), "/api/v1/auth/login", loginBody(admin.email()));
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        JsonNode body = mapper.readTree(login.getBody());
        assertThat(body.get("errorCode").asText()).isEqualTo("AUTH_008");
        return body.at("/details/mfaChallengeToken").asText();
    }

    private ResponseEntity<String> verify(String challengeToken, String code) {
        return post(json(), "/api/v1/auth/mfa/verify",
                "{\"challengeToken\":\"" + challengeToken + "\",\"code\":\"" + code + "\"}");
    }

    /** claimStep needs a transaction (it is a modifying query); the service supplies one in
     *  production, so the tests that call it directly have to as well. */
    private int claim(Admin admin, long step) {
        Integer rows = new TransactionTemplate(transactionManager)
                .execute(status -> credentials.claimStep(admin.id(), step));
        return rows == null ? 0 : rows;
    }

    private void assertRefusedAsInvalidCode(ResponseEntity<String> response) throws Exception {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(mapper.readTree(response.getBody()).get("errorCode").asText()).isEqualTo("AUTH_009");
    }

    @Test
    @DisplayName("a code that has signed someone in cannot sign anyone in again")
    void aCodeWorksOnce() throws Exception {
        Admin admin = enrolledAdmin(Instant.now().minusSeconds(30));
        Instant at = Instant.now();
        String code = TotpGenerator.codeAt(admin.secret(), at);

        assertThat(verify(challengeFor(admin), code).getStatusCode()).isEqualTo(HttpStatus.OK);

        // Someone who saw that code now has the password and a fresh challenge, seconds later.
        String secondChallenge = challengeFor(admin);
        assertRefusedAsInvalidCode(verify(secondChallenge, code));

        // The step is recorded on the row, so a restart or another instance would refuse it too.
        assertThat(credentials.findByUserId(admin.id()).orElseThrow().getLastUsedStep())
                .isEqualTo(TotpGenerator.stepAt(at));

        // A refused replay does not burn the challenge or lock the real admin out: their next
        // code, from the next step, still completes that same sign-in.
        String next = TotpGenerator.codeAt(admin.secret(), at.plusSeconds(30));
        assertThat(verify(secondChallenge, next).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("the code typed to finish enrolment cannot also open the very next sign-in")
    void theEnrolmentCodeCountsAsUsed() throws Exception {
        Instant at = Instant.now();
        Admin admin = enrolledAdmin(at);

        assertRefusedAsInvalidCode(verify(challengeFor(admin), TotpGenerator.codeAt(admin.secret(), at)));
    }

    @Test
    @DisplayName("two requests carrying the same fresh code at the same moment: exactly one gets in")
    void simultaneousRequestsWithTheSameCodeAreSerialised() throws Exception {
        Admin admin = enrolledAdmin(Instant.now().minusSeconds(30));
        String code = TotpGenerator.currentCode(admin.secret());
        String challengeA = challengeFor(admin);
        String challengeB = challengeFor(admin);

        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<ResponseEntity<String>> a = pool.submit(() -> { go.await(); return verify(challengeA, code); });
            Future<ResponseEntity<String>> b = pool.submit(() -> { go.await(); return verify(challengeB, code); });
            go.countDown();
            List<HttpStatus> statuses = List.of(
                    HttpStatus.valueOf(a.get(30, TimeUnit.SECONDS).getStatusCode().value()),
                    HttpStatus.valueOf(b.get(30, TimeUnit.SECONDS).getStatusCode().value()));

            // Whether or not the two actually overlapped, the invariant is the same: one winner.
            assertThat(statuses).containsExactlyInAnyOrder(HttpStatus.OK, HttpStatus.UNAUTHORIZED);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("claiming a step is atomic: under contention exactly one caller wins, every round")
    void claimStepHasExactlyOneWinnerUnderContention() throws Exception {
        Admin admin = enrolledAdmin(Instant.now().minusSeconds(30));
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            // One fresh step per round, all later than the step enrolment recorded.
            long first = FUTURE_STEP.getAsLong();
            for (long step = first; step < first + 30; step++) {
                long thisStep = step;
                CountDownLatch go = new CountDownLatch(1);
                List<Future<Integer>> results = new ArrayList<>();
                for (int i = 0; i < threads; i++) {
                    Callable<Integer> contender = () -> {
                        go.await();
                        return claim(admin, thisStep);
                    };
                    results.add(pool.submit(contender));
                }
                go.countDown();
                int winners = 0;
                for (Future<Integer> result : results) winners += result.get(30, TimeUnit.SECONDS);

                assertThat(winners).as("winners for step %d", thisStep).isEqualTo(1);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("a step at or below the recorded one can never be claimed, only a later one")
    void claimStepOnlyMovesForward() throws Exception {
        Admin admin = enrolledAdmin(Instant.now().minusSeconds(30));

        long step = FUTURE_STEP.getAsLong();

        assertThat(claim(admin, step)).isEqualTo(1);
        assertThat(claim(admin, step)).isEqualTo(0);     // the same step
        assertThat(claim(admin, step - 1)).isEqualTo(0); // an earlier one
        assertThat(claim(admin, step + 1)).isEqualTo(1); // a later one
        assertThat(credentials.findByUserId(admin.id()).orElseThrow().getLastUsedStep()).isEqualTo(step + 1);
    }

    @Test
    @DisplayName("an ordinary save of a stale copy of the row cannot overwrite a claimed step")
    void aStaleEntityCannotWriteBackAnOlderStep() throws Exception {
        Admin admin = enrolledAdmin(Instant.now().minusSeconds(30));
        // A copy read before another request claims a step, then saved after: the shape of the
        // overwrite the read-only column mapping exists to prevent.
        AdminTotpCredential stale = credentials.findByUserId(admin.id()).orElseThrow();
        Long before = stale.getLastUsedStep();
        long claimed = FUTURE_STEP.getAsLong();
        assertThat(claim(admin, claimed)).isEqualTo(1);

        stale.storeSecret(stale.secret()); // make it dirty so save() really issues an UPDATE
        credentials.save(stale);

        assertThat(before).isNotEqualTo(claimed); // the copy really was older than the claim
        assertThat(credentials.findByUserId(admin.id()).orElseThrow().getLastUsedStep()).isEqualTo(claimed);
    }
}
