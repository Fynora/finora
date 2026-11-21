package com.finora.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
import com.finora.entity.User;
import com.finora.repository.UserRepository;
import com.finora.service.PlatformSettingsService;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Audit F-09 (2026-11-20). Wrong passwords lock password sign-in for a while, and anyone who knows
 * the address can cause that. A locked owner is told only "Invalid credentials", so they reset
 * their password, and the reset left the lock in place: the new password was refused too until the
 * lock ran out.
 *
 * <p>A completed reset now clears the lock. By then the owner has shown they hold the mailbox and
 * the phone. Over real HTTP, each test from its own client address so one test's sign-in attempts
 * do not count against another's rate limit.
 */
@Import(TestPhoneVerificationConfig.class)
class ResetClearsLockoutIT extends AbstractIntegrationTest {

    private static final String FIRST_PASSWORD = "OriginalPass123";
    private static final String NEW_PASSWORD = "kettle#Orbit42river";
    private static final String WRONG_PASSWORD = "not-the-password-at-all";
    private static final AtomicInteger CLIENT_IP_COUNTER = new AtomicInteger(200);

    @DynamicPropertySource
    static void enableTrustProxyHeaders(DynamicPropertyRegistry registry) {
        registry.add("app.security.trust-proxy-headers", () -> "true");
    }

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private PlatformSettingsService platformSettingsService;
    @Autowired private org.springframework.transaction.PlatformTransactionManager transactionManager;
    private final ObjectMapper mapper = new ObjectMapper();

    private record Person(String email, String phoneNumber, String clientIp) {}

    private Person newPerson() {
        return new Person("reset-lock-" + UUID.randomUUID() + "@example.com",
                "+9198763" + (10000 + new java.util.Random().nextInt(90000)),
                "203.0.113." + CLIENT_IP_COUNTER.getAndIncrement());
    }

    private ResponseEntity<String> post(String path, Person person, String token, String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Forwarded-For", person.clientIp() + ", 100.64.0.1");
        if (token != null) headers.setBearerAuth(token);
        return restTemplate.postForEntity(path, new HttpEntity<>(json, headers), String.class);
    }

    private Person registerVerified() throws Exception {
        return registerVerifiedWithSession().person();
    }

    private record SignedIn(Person person, String token, String refreshToken) {}

    private SignedIn registerVerifiedWithSession() throws Exception {
        Person person = newPerson();
        ResponseEntity<String> response = post("/api/v1/auth/register", person, null, """
                {"email": "%s", "password": "%s", "fullName": "Meera Krishnan", "phoneNumber": "%s"}
                """.formatted(person.email(), FIRST_PASSWORD, person.phoneNumber()));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = mapper.readTree(response.getBody()).get("data");
        assertThat(post("/api/v1/phone/verify", person, data.get("token").asText(), """
                {"firebaseIdToken": "%s"}
                """.formatted(FakePhoneVerificationProvider.tokenFor(person.phoneNumber()))).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        return new SignedIn(person, data.get("token").asText(), data.get("refreshToken").asText());
    }

    /** The in-app change: current password, then the phone, then the new password. */
    private ResponseEntity<String> changePassword(SignedIn user, String newPassword) throws Exception {
        ResponseEntity<String> start = post("/api/v1/users/me/password-change/start", user.person(), user.token(), """
                {"currentPassword": "%s"}
                """.formatted(FIRST_PASSWORD));
        assertThat(start.getStatusCode()).isEqualTo(HttpStatus.OK);
        String sessionId = mapper.readTree(start.getBody()).get("data").get("sessionId").asText();
        assertThat(post("/api/v1/users/me/password-change/verify-otp", user.person(), user.token(), """
                {"sessionId": "%s", "firebaseIdToken": "%s"}
                """.formatted(sessionId, FakePhoneVerificationProvider.tokenFor(user.person().phoneNumber())))
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        return post("/api/v1/users/me/password-change/complete", user.person(), user.token(), """
                {"sessionId": "%s", "newPassword": "%s", "signOutOtherDevices": false, "currentRefreshToken": "%s"}
                """.formatted(sessionId, newPassword, user.refreshToken()));
    }

    private ResponseEntity<String> login(Person person, String password) {
        return post("/api/v1/auth/login", person, null, """
                {"identifier": "%s", "password": "%s"}
                """.formatted(person.email(), password));
    }

    private void wrongPasswords(Person person, int times) {
        for (int i = 0; i < times; i++) {
            assertThat(login(person, WRONG_PASSWORD).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    private int attemptsThatLock() {
        return platformSettingsService.getEntity().getMaxFailedLoginAttempts();
    }

    private User stored(Person person) {
        return userRepository.findByEmailIgnoreCaseAndAccountScope(person.email(), User.SCOPE_USER).orElseThrow();
    }

    private String resetToken(Person person) throws Exception {
        ResponseEntity<String> forgot = post("/api/v1/auth/forgot-password", person, null, """
                {"email": "%s"}
                """.formatted(person.email()));
        assertThat(forgot.getStatusCode()).isEqualTo(HttpStatus.OK);
        String link = mapper.readTree(forgot.getBody()).get("data").get("devResetLink").asText();
        return link.substring(link.indexOf("token=") + "token=".length());
    }

    private ResponseEntity<String> reset(Person person, String token, String newPassword) {
        return post("/api/v1/auth/reset-password", person, null, """
                {"token": "%s", "firebaseIdToken": "%s", "newPassword": "%s"}
                """.formatted(token, FakePhoneVerificationProvider.tokenFor(person.phoneNumber()), newPassword));
    }

    @Test
    void aLockedOwnerWhoResetsTheirPassword_canSignInWithTheNewOneAtOnce() throws Exception {
        Person person = registerVerified();
        wrongPasswords(person, attemptsThatLock());
        assertThat(stored(person).getLockedUntil()).as("the account is locked").isNotNull();
        assertThat(login(person, FIRST_PASSWORD).getStatusCode())
                .as("while locked, even the right password is refused").isEqualTo(HttpStatus.UNAUTHORIZED);

        assertThat(reset(person, resetToken(person), NEW_PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);

        User afterReset = stored(person);
        assertThat(afterReset.getLockedUntil()).isNull();
        assertThat(afterReset.getFailedLoginAttempts()).isZero();
        assertThat(login(person, NEW_PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /** Someone still signed in on one device, locked out by a stranger's guesses, who changes their
     *  password in the app. They have given the current password and proved the phone. */
    @Test
    void aLockedOwnerWhoChangesTheirPasswordInTheApp_canSignInWithTheNewOneAtOnce() throws Exception {
        SignedIn user = registerVerifiedWithSession();
        wrongPasswords(user.person(), attemptsThatLock());
        assertThat(stored(user.person()).getLockedUntil()).as("the account is locked").isNotNull();

        assertThat(changePassword(user, NEW_PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);

        User afterChange = stored(user.person());
        assertThat(afterChange.getLockedUntil()).isNull();
        assertThat(afterChange.getFailedLoginAttempts()).isZero();
        assertThat(login(user.person(), NEW_PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /** Wrong passwords sent at the same moment, each from its own address, must each count. Counted
     *  from what each request read at its start, they all counted as "one more than none", and the
     *  account never locked however many arrived together. */
    @Test
    void wrongPasswordsSentAtTheSameMoment_areEachCounted_andLockTheAccount() throws Exception {
        Person person = registerVerified();
        int attempts = attemptsThatLock();
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(attempts);
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        try {
            java.util.List<java.util.concurrent.Future<HttpStatus>> answers = new java.util.ArrayList<>();
            for (int i = 0; i < attempts; i++) {
                Person fromElsewhere = new Person(person.email(), person.phoneNumber(),
                        "203.0.113." + CLIENT_IP_COUNTER.getAndIncrement());
                answers.add(pool.submit(() -> {
                    go.await();
                    return (HttpStatus) login(fromElsewhere, WRONG_PASSWORD).getStatusCode();
                }));
            }
            go.countDown();
            for (java.util.concurrent.Future<HttpStatus> answer : answers) {
                assertThat(answer.get(60, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(HttpStatus.UNAUTHORIZED);
            }
        } finally {
            pool.shutdownNow();
        }

        User afterwards = stored(person);
        assertThat(afterwards.getFailedLoginAttempts()).isEqualTo(attempts);
        assertThat(afterwards.getLockedUntil()).as("the account is locked").isNotNull();
    }

    /** Twice the limit, all at once: the limit is counted, the account locks, and the rest are
     *  refused without being counted or having their password looked at. */
    @Test
    void moreWrongPasswordsAtOnceThanTheLimit_countOnlyUpToTheLimit() throws Exception {
        Person person = registerVerified();
        int limit = attemptsThatLock();
        int sent = limit * 2;
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(sent);
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        try {
            java.util.List<java.util.concurrent.Future<HttpStatus>> answers = new java.util.ArrayList<>();
            for (int i = 0; i < sent; i++) {
                Person fromElsewhere = new Person(person.email(), person.phoneNumber(),
                        "203.0.113." + CLIENT_IP_COUNTER.getAndIncrement());
                answers.add(pool.submit(() -> {
                    go.await();
                    return (HttpStatus) login(fromElsewhere, WRONG_PASSWORD).getStatusCode();
                }));
            }
            go.countDown();
            for (java.util.concurrent.Future<HttpStatus> answer : answers) {
                assertThat(answer.get(60, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(HttpStatus.UNAUTHORIZED);
            }
        } finally {
            pool.shutdownNow();
        }

        User afterwards = stored(person);
        assertThat(afterwards.getFailedLoginAttempts()).isEqualTo(limit);
        assertThat(afterwards.getLockedUntil()).isNotNull();
    }

    /** The attempt that reaches the limit is counted, and locked, before its password is checked.
     *  When that password is right the person signs in and both are cleared. */
    @Test
    void theRightPasswordOnTheLastAllowedAttempt_signsIn_andClearsTheCount() throws Exception {
        Person person = registerVerified();
        wrongPasswords(person, attemptsThatLock() - 1);

        assertThat(login(person, FIRST_PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);

        User afterwards = stored(person);
        assertThat(afterwards.getFailedLoginAttempts()).isZero();
        assertThat(afterwards.getLockedUntil()).isNull();
    }

    /** A suspended account's owner giving the right password is told about the suspension. That
     *  is not a wrong password, so it is not counted, and it does not clear the count either. Here
     *  it arrives as the attempt that would have reached the limit: no lock is left behind. */
    @Test
    void theRightPasswordOnASuspendedAccount_leavesTheCountAsItWas() throws Exception {
        Person person = registerVerified();
        int beforeLock = attemptsThatLock() - 1;
        wrongPasswords(person, beforeLock);
        User suspended = stored(person);
        suspended.setStatus(User.STATUS_SUSPENDED);
        userRepository.save(suspended);

        assertThat(login(person, FIRST_PASSWORD).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        User afterwards = stored(person);
        assertThat(afterwards.getFailedLoginAttempts()).isEqualTo(beforeLock);
        assertThat(afterwards.getLockedUntil()).isNull();
    }

    /** A lock that has run out takes its count with it: the next wrong password is the first. */
    @Test
    void aWrongPasswordAfterTheLockHasRunOut_isCountedAsTheFirst() throws Exception {
        Person person = registerVerified();
        wrongPasswords(person, attemptsThatLock());
        User locked = stored(person);
        locked.setLockedUntil(java.time.Instant.now().minusSeconds(5));
        userRepository.save(locked);

        wrongPasswords(person, 1);

        User afterwards = stored(person);
        assertThat(afterwards.getFailedLoginAttempts()).isEqualTo(1);
        assertThat(afterwards.getLockedUntil()).isNull();
        assertThat(login(person, FIRST_PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /** A lock that lands while a reset is in progress: after the reset read the account, before it
     *  wrote. Setting the fields on what it read sends nothing, because they were already empty
     *  there, so the lock is lifted by a statement of its own. */
    @Test
    void aLockThatLandsAfterTheAccountWasRead_isStillLifted() throws Exception {
        Person person = registerVerified();
        java.util.UUID id = stored(person).getId();
        var tx = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        var separate = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        separate.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        tx.executeWithoutResult(status -> {
            User readByTheReset = userRepository.findById(id).orElseThrow();
            separate.executeWithoutResult(inner -> {
                User locked = userRepository.findById(id).orElseThrow();
                locked.setFailedLoginAttempts(5);
                locked.setLockedUntil(java.time.Instant.now().plusSeconds(900));
                userRepository.save(locked);
            });
            readByTheReset.setFailedLoginAttempts(0);
            readByTheReset.setLockedUntil(null);
            userRepository.save(readByTheReset);
            userRepository.clearWrongPasswordLock(id);
        });

        assertThat(stored(person).getLockedUntil()).isNull();
        assertThat(stored(person).getFailedLoginAttempts()).isZero();
    }

    /** Only a reset that goes through clears the lock. A refused new password changes nothing. */
    @Test
    void aResetThatIsRefused_leavesTheLockInPlace() throws Exception {
        Person person = registerVerified();
        wrongPasswords(person, attemptsThatLock());

        assertThat(reset(person, resetToken(person), "password123").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        assertThat(stored(person).getLockedUntil()).isNotNull();
        assertThat(login(person, FIRST_PASSWORD).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /** Wrong passwords short of a lock are forgotten too, so the first slip with the new password
     *  does not lock the account on the strength of guesses made against the old one. */
    @Test
    void aReset_alsoForgetsWrongPasswordsThatHadNotYetLockedTheAccount() throws Exception {
        Person person = registerVerified();
        int beforeLock = attemptsThatLock() - 1;
        wrongPasswords(person, beforeLock);
        assertThat(stored(person).getFailedLoginAttempts()).isEqualTo(beforeLock);

        assertThat(reset(person, resetToken(person), NEW_PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(stored(person).getFailedLoginAttempts()).isZero();
        wrongPasswords(person, 1);
        assertThat(login(person, NEW_PASSWORD).getStatusCode())
                .as("one slip after the reset does not lock the account").isEqualTo(HttpStatus.OK);
    }
}
