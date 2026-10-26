package com.finora.onboarding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
import com.finora.entity.User;
import com.finora.repository.RefreshTokenRepository;
import com.finora.repository.UserRepository;
import com.finora.security.JwtService;
import com.finora.testsupport.TestSessions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sign-up, sign-in and onboarding through the real security chain, controller advice and
 * database, fed what broke them in live testing on a local backend (2026-10-26):
 * <ul>
 *   <li>a password within the 72-character limit but over BCrypt's 72 BYTES -- 25 or more Hindi
 *       letters, or emoji -- was refused as "One of the request's parameters is not valid", on
 *       sign-up, reset and change alike;</li>
 *   <li>a focus key in another case or with spaces passed the check and was stored as typed, so
 *       the same answer was saved twice and once in a form the app does not recognise;</li>
 *   <li>saving the focus answers twice at once failed 6 times out of 10, and ticking one checklist
 *       item twice at once failed once, each as a 409 "refresh and try again".</li>
 * </ul>
 */
class SignInAndOnboardingBreaksIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private UserFinancialFocusRepository focusRepository;
    @Autowired private UserChecklistEventRepository checklistRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    private final ObjectMapper mapper = new ObjectMapper();

    private static final String HINDI_25 = "क".repeat(25);   // 25 characters, 75 bytes
    private static final String HINDI_24 = "क".repeat(24);   // 24 characters, exactly 72 bytes

    private User createUser() {
        User user = new User();
        user.setEmail("signin-onboarding-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Sign In IT User");
        user.setAccountScope(User.SCOPE_USER);
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    private ResponseEntity<String> anon(String path, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private ResponseEntity<String> send(HttpMethod method, String path, String body, User user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestSessions.accessTokenFor(jwtService, refreshTokens, user));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(path, method, new HttpEntity<>(body, headers), String.class);
    }

    private JsonNode json(ResponseEntity<String> response) throws Exception {
        return mapper.readTree(response.getBody());
    }

    /** A synthetic Indian mobile number no other test uses: 9 followed by nine digits. */
    private static String phone() {
        return "9" + String.format("%09d", Math.floorMod(UUID.randomUUID().getMostSignificantBits(), 1_000_000_000L));
    }

    private ResponseEntity<String> register(String email, String password) {
        return anon("/api/v1/auth/register", "{\"email\":\"" + email + "\",\"password\":\"" + password
                + "\",\"fullName\":\"Hindi Password\",\"phoneNumber\":\"" + phone() + "\"}");
    }

    private void assertTooLongPassword(ResponseEntity<String> response) throws Exception {
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(response).get("message").asText()).contains("too long").contains("72 bytes");
    }

    // ------------------------------------------------------------------ passwords

    @Test
    void aSignUpPasswordOver72Bytes_isRefusedWithAReason() throws Exception {
        assertThat(HINDI_25.getBytes(StandardCharsets.UTF_8)).hasSize(75);
        assertTooLongPassword(register("hindi25-" + UUID.randomUUID() + "@example.com", HINDI_25));
        assertTooLongPassword(register("emoji-" + UUID.randomUUID() + "@example.com", "😀".repeat(20)));
    }

    @Test
    void aSignUpPasswordOfExactly72Bytes_signsUpAndSignsIn() throws Exception {
        assertThat(HINDI_24.getBytes(StandardCharsets.UTF_8)).hasSize(72);
        String email = "hindi24-" + UUID.randomUUID() + "@example.com";
        ResponseEntity<String> created = register(email, HINDI_24);
        assertThat(created.getStatusCode()).as(created.getBody()).isEqualTo(HttpStatus.OK);
        User user = userRepository.findByEmailIgnoreCaseAndAccountScope(email, User.SCOPE_USER).orElseThrow();
        user.setPhoneVerified(true);
        userRepository.save(user);

        ResponseEntity<String> signedIn = anon("/api/v1/auth/login",
                "{\"identifier\":\"" + email + "\",\"password\":\"" + HINDI_24 + "\"}");
        assertThat(signedIn.getStatusCode()).as(signedIn.getBody()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void aResetOrChangedPasswordOver72Bytes_isRefusedWithAReason() throws Exception {
        assertTooLongPassword(anon("/api/v1/auth/reset-password",
                "{\"token\":\"t\",\"firebaseIdToken\":\"f\",\"newPassword\":\"" + HINDI_25 + "\"}"));
        assertTooLongPassword(send(HttpMethod.POST, "/api/v1/users/me/password-change/complete",
                "{\"sessionId\":\"s\",\"newPassword\":\"" + HINDI_25 + "\",\"signOutOtherDevices\":false}", createUser()));
    }

    // ------------------------------------------------------------------ onboarding

    @Test
    void focusKeysAreSavedByTheirName_onceEach() throws Exception {
        User user = createUser();
        ResponseEntity<String> saved = send(HttpMethod.POST, "/api/v1/onboarding/financial-focus",
                "{\"focusKeys\":[\"TRACK_SPENDING\",\"track_spending \",\" Reduce_Debt\"]}", user);

        assertThat(saved.getStatusCode()).as(saved.getBody()).isEqualTo(HttpStatus.OK);
        List<String> stored = focusRepository.findByUserId(user.getId()).stream()
                .map(UserFinancialFocus::getFocusKey).sorted().toList();
        assertThat(stored).containsExactly("REDUCE_DEBT", "TRACK_SPENDING");
        assertThat(json(saved).get("data").get("financialFocus").toString()).doesNotContain("track_spending ");
    }

    @Test
    void savingTheFocusAnswersTwiceAtOnce_neverFails() throws Exception {
        User user = createUser();
        List<Integer> codes = concurrently(10, () -> send(HttpMethod.POST, "/api/v1/onboarding/financial-focus",
                "{\"focusKeys\":[\"TRACK_SPENDING\",\"REDUCE_DEBT\"]}", user).getStatusCode().value());

        assertThat(codes).containsOnly(200);
        assertThat(focusRepository.findByUserId(user.getId())).hasSize(2);
    }

    @Test
    void changingTheFocusAnswers_replacesThem() throws Exception {
        User user = createUser();
        send(HttpMethod.POST, "/api/v1/onboarding/financial-focus", "{\"focusKeys\":[\"TRACK_SPENDING\",\"REDUCE_DEBT\"]}", user);
        send(HttpMethod.POST, "/api/v1/onboarding/financial-focus", "{\"focusKeys\":[\"SAVE_FOR_GOAL\"]}", user);
        assertThat(focusRepository.findByUserId(user.getId())).extracting(UserFinancialFocus::getFocusKey)
                .containsExactly("SAVE_FOR_GOAL");
        send(HttpMethod.POST, "/api/v1/onboarding/financial-focus", "{\"focusKeys\":[]}", user);
        assertThat(focusRepository.findByUserId(user.getId())).isEmpty();
    }

    /** Live, 1 tap in 10 lost the race; five users of ten taps each, so the race is all but
     *  certain to be run at least once. */
    @Test
    void tickingAChecklistItemTwiceAtOnce_neverFails() throws Exception {
        for (int round = 0; round < 5; round++) {
            User user = createUser();
            List<Integer> codes = concurrently(10, () -> send(HttpMethod.POST,
                    "/api/v1/onboarding/checklist/VIEW_INSIGHTS/complete", null, user).getStatusCode().value());

            assertThat(codes).as("round " + round).containsOnly(200);
            assertThat(checklistRepository.findByUserId(user.getId())).hasSize(1);
        }
    }

    private static List<Integer> concurrently(int n, Callable<Integer> call) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) futures.add(pool.submit(call));
            List<Integer> out = new ArrayList<>();
            for (Future<Integer> f : futures) out.add(f.get());
            return out;
        } finally {
            pool.shutdownNow();
        }
    }
}
