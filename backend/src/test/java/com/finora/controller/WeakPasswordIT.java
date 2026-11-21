package com.finora.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
import com.finora.dto.AuthDtos.RegisterRequest;
import com.finora.exception.ApiException;
import com.finora.repository.UserRepository;
import com.finora.service.AuthService;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Audit F-08 (2026-11-20). The only rule for a new password was its length, so "password" and
 * "12345678" were accepted. Every way of setting a password now refuses a commonly used one and
 * one made from the person's own name, email address or phone number.
 *
 * <p>Over real HTTP, so what is checked is what the apps receive: the status and the sentence
 * they show. Each test has its own client address, for the reason PasswordChangeFlowIT gives.
 */
@Import(TestPhoneVerificationConfig.class)
class WeakPasswordIT extends AbstractIntegrationTest {

    private static final String COMMON = "That password is one of the most commonly used, so it is "
            + "easy to guess. Choose a different one.";
    private static final String PERSONAL = "Do not use your name, email address or phone number as "
            + "your password. Choose a different one.";

    // The value PasswordChangeFlowIT signs up with. Made up, and not on the list.
    private static final String SINGLE_WORD = "A single word, even with numbers or symbols added, is "
            + "easy to guess. Use several words, or 12 or more characters.";

    private static final String FIRST_PASSWORD = "OriginalPass123";
    private static final String NAME = "Meera Krishnan";
    private static final AtomicInteger CLIENT_IP_COUNTER = new AtomicInteger(1);

    @DynamicPropertySource
    static void enableTrustProxyHeaders(DynamicPropertyRegistry registry) {
        registry.add("app.security.trust-proxy-headers", () -> "true");
    }

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private AuthService authService;
    private final ObjectMapper mapper = new ObjectMapper();

    private record Person(String email, String phoneNumber, String clientIp) {}

    private record Registered(Person person, String token, String refreshToken) {}

    private Person newPerson() {
        return new Person("weak-pw-" + UUID.randomUUID() + "@example.com",
                "+9198764" + (10000 + new java.util.Random().nextInt(90000)),
                "198.51.100." + CLIENT_IP_COUNTER.getAndIncrement());
    }

    private HttpHeaders headers(Person person, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Forwarded-For", person.clientIp() + ", 100.64.0.1");
        if (token != null) headers.setBearerAuth(token);
        return headers;
    }

    private ResponseEntity<String> post(String path, Person person, String token, String json) {
        return restTemplate.postForEntity(path, new HttpEntity<>(json, headers(person, token)), String.class);
    }

    private ResponseEntity<String> register(Person person, String password) {
        return post("/api/v1/auth/register", person, null, """
                {"email": "%s", "password": "%s", "fullName": "%s", "phoneNumber": "%s"}
                """.formatted(person.email(), password, NAME, person.phoneNumber()));
    }

    private Registered registerVerified() throws Exception {
        Person person = newPerson();
        ResponseEntity<String> response = register(person, FIRST_PASSWORD);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = mapper.readTree(response.getBody()).get("data");
        String token = data.get("token").asText();
        assertThat(post("/api/v1/phone/verify", person, token, """
                {"firebaseIdToken": "%s"}
                """.formatted(FakePhoneVerificationProvider.tokenFor(person.phoneNumber()))).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        return new Registered(person, token, data.get("refreshToken").asText());
    }

    private ResponseEntity<String> login(Person person, String password) {
        return post("/api/v1/auth/login", person, null, """
                {"identifier": "%s", "password": "%s"}
                """.formatted(person.email(), password));
    }

    private void assertRefused(ResponseEntity<String> response, String sentence) throws Exception {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(mapper.readTree(response.getBody()).get("message").asText()).isEqualTo(sentence);
    }

    // ---- sign-up ----

    @Test
    void signUp_withACommonPassword_isRefused_andNoAccountIsCreated() throws Exception {
        Person person = newPerson();

        assertRefused(register(person, "password123"), COMMON);

        assertThat(accountExists(person)).isFalse();
    }

    private boolean accountExists(Person person) {
        return userRepository.existsByEmailIgnoreCaseAndAccountScope(person.email(), "USER");
    }

    /** Not on the list as typed. "hello123" is. */
    @Test
    void signUp_withACommonPasswordAndASymbolAdded_isRefused() throws Exception {
        assertRefused(register(newPerson(), "Hello@123"), COMMON);
    }

    /** On no list and nobody's name: one word, a symbol and a number, in under 12 characters. */
    @Test
    void signUp_withASingleShortWordAndNumbers_isRefused() throws Exception {
        assertRefused(register(newPerson(), "Mumbai@123"), SINGLE_WORD);
    }

    @Test
    void signUp_withTheirOwnNameAndDigits_isRefused() throws Exception {
        assertRefused(register(newPerson(), "Krishnan@2026"), PERSONAL);
    }

    @Test
    void signUp_withTheirOwnPhoneNumber_isRefused() throws Exception {
        Person person = newPerson();

        assertRefused(register(person, person.phoneNumber().substring(3)), PERSONAL);
    }

    @Test
    void signUp_withTheirOwnEmailAddress_isRefused() throws Exception {
        Person person = newPerson();

        assertRefused(register(person, person.email()), PERSONAL);
    }

    @Test
    void signUp_withAnUncommonPassword_works() {
        assertThat(register(newPerson(), FIRST_PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /** An address that is already registered must not be discoverable through the password
     *  check's order: the weak password is refused the same way whether or not the address is taken. */
    @Test
    void signUp_withACommonPassword_saysTheSameForATakenAddress() throws Exception {
        Registered existing = registerVerified();
        Person sameAddress = new Person(existing.person().email(), newPerson().phoneNumber(),
                existing.person().clientIp());

        assertRefused(register(sameAddress, "password123"), COMMON);
    }

    // ---- an admin creating an account ----

    @Test
    void anAdminCreatingAnAccount_cannotGiveItACommonPassword() {
        Person person = newPerson();

        assertThatThrownBy(() -> authService.adminCreateUser(
                new RegisterRequest(person.email(), "qwertyuiop", NAME, person.phoneNumber(), null),
                UUID.randomUUID()))
                .isInstanceOf(ApiException.class)
                .hasMessage(COMMON);

        assertThat(accountExists(person)).isFalse();
    }

    // ---- change ----

    private String verifiedChangeSession(Registered user) throws Exception {
        ResponseEntity<String> start = post("/api/v1/users/me/password-change/start", user.person(), user.token(), """
                {"currentPassword": "%s"}
                """.formatted(FIRST_PASSWORD));
        assertThat(start.getStatusCode()).isEqualTo(HttpStatus.OK);
        String sessionId = mapper.readTree(start.getBody()).get("data").get("sessionId").asText();
        assertThat(post("/api/v1/users/me/password-change/verify-otp", user.person(), user.token(), """
                {"sessionId": "%s", "firebaseIdToken": "%s"}
                """.formatted(sessionId, FakePhoneVerificationProvider.tokenFor(user.person().phoneNumber())))
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        return sessionId;
    }

    private ResponseEntity<String> completeChange(Registered user, String sessionId, String newPassword) {
        return post("/api/v1/users/me/password-change/complete", user.person(), user.token(), """
                {"sessionId": "%s", "newPassword": "%s", "signOutOtherDevices": false, "currentRefreshToken": "%s"}
                """.formatted(sessionId, newPassword, user.refreshToken()));
    }

    /** A refused password must leave everything as it was, and must not cost the person their
     *  verified step: they pick another password and carry on. */
    @Test
    void change_toACommonPassword_isRefused_andTheSameSessionThenAcceptsAGoodOne() throws Exception {
        Registered user = registerVerified();
        String sessionId = verifiedChangeSession(user);

        assertRefused(completeChange(user, sessionId, "iloveyou123"), COMMON);
        assertRefused(completeChange(user, sessionId, "meera12345"), PERSONAL);
        assertThat(login(user.person(), FIRST_PASSWORD).getStatusCode())
                .as("the old password still works after the refusals").isEqualTo(HttpStatus.OK);

        assertThat(completeChange(user, sessionId, "kettle#Orbit42river").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(login(user.person(), "kettle#Orbit42river").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ---- reset ----

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

    /** A refused password must not use up the reset link. */
    @Test
    void reset_toACommonPassword_isRefused_andTheSameLinkThenAcceptsAGoodOne() throws Exception {
        Registered user = registerVerified();
        String token = resetToken(user.person());

        assertRefused(reset(user.person(), token, "qwertyuiop"), COMMON);
        assertRefused(reset(user.person(), token, "MeeraKrishnan1"), PERSONAL);
        assertThat(login(user.person(), FIRST_PASSWORD).getStatusCode())
                .as("the old password still works after the refusals").isEqualTo(HttpStatus.OK);

        assertThat(reset(user.person(), token, "kettle#Orbit42river").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(login(user.person(), "kettle#Orbit42river").getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
