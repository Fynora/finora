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
        return person;
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
