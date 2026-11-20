package com.finora.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
import com.finora.entity.User;
import com.finora.repository.UserRepository;
import com.finora.service.RefreshTokenService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A real sign-in over HTTP writes the "your account was just signed in to" alert into the real
 * notifications table, through the real template rows V332 seeds, and the things that are not a
 * sign-in write none. Every assertion is scoped to one freshly made user, because the table is
 * shared by the whole suite.
 */
@AutoConfigureMockMvc
class SignInAlertIT extends AbstractIntegrationTest {

    private static final String PASSWORD = "SecurePass123";
    private static final String IPHONE_SAFARI = "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) "
            + "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private RefreshTokenService refreshTokenService;

    private User newUser(boolean emailVerified) {
        User user = new User();
        user.setEmail("sign-in-alert-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setFullName("Sign In Alert");
        user.setPhoneVerified(true);
        user.setEmailVerified(emailVerified);
        return userRepository.save(user);
    }

    private void signIn(User user, String password, int expectedStatus) throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("User-Agent", IPHONE_SAFARI)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "identifier", user.getEmail(), "password", password, "scope", "USER"))))
                .andExpect(status().is(expectedStatus));
    }

    private List<Map<String, Object>> alertsFor(UUID userId) {
        return jdbcTemplate.queryForList("""
                SELECT channel, category, priority, title, message, notification_key
                FROM notifications WHERE user_id = ? AND type = 'NEW_SIGN_IN' ORDER BY channel
                """, userId);
    }

    @Test
    void aSignInTellsTheOwnerByEmailAndPush_withTheDeviceBrowserAndTime() throws Exception {
        User user = newUser(true);

        signIn(user, PASSWORD, 200);

        List<Map<String, Object>> alerts = alertsFor(user.getId());
        assertThat(alerts).extracting(row -> row.get("channel")).containsExactly("EMAIL", "PUSH");
        assertThat(alerts).allSatisfy(row -> {
            assertThat(row.get("category")).as("a customer cannot switch this off").isEqualTo("SECURITY");
            assertThat(row.get("priority")).isEqualTo("HIGH");
        });

        UUID sessionId = jdbcTemplate.queryForObject(
                "SELECT session_id FROM refresh_tokens WHERE user_id = ?", UUID.class, user.getId());
        assertThat(alerts).extracting(row -> row.get("notification_key"))
                .as("keyed on the session the sign-in created")
                .containsExactly("NEW_SIGN_IN_" + sessionId + ":EMAIL", "NEW_SIGN_IN_" + sessionId + ":PUSH");

        Map<String, Object> email = alerts.get(0);
        assertThat(email.get("title")).isEqualTo("New sign-in to your Fynora account");
        assertThat((String) email.get("message"))
                .contains("Device: iPhone.")
                .contains("App or browser: Safari.")
                .contains("IP address: ")
                .contains(" IST")
                .contains("change your password now")
                .as("every placeholder was filled in")
                .doesNotContain("{{");

        Map<String, Object> push = alerts.get(1);
        assertThat((String) push.get("message"))
                .isEqualTo("Your Fynora account was just signed in to on iPhone. Not you? Change your password.");
    }

    /** The phone app has no browser user agent; it is named from the platform it says it is. */
    @Test
    void aSignInFromThePhoneAppIsNamedAsTheApp() throws Exception {
        User user = newUser(true);

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("User-Agent", "okhttp/4.12.0")
                        .header("X-Client-Platform", "MOBILE_ANDROID")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "identifier", user.getEmail(), "password", PASSWORD, "scope", "USER"))))
                .andExpect(status().isOk());

        assertThat((String) alertsFor(user.getId()).get(0).get("message"))
                .contains("Device: Android phone.")
                .contains("App or browser: Fynora app.")
                .doesNotContain("Other");
    }

    /**
     * A suspended customer's sign-in is refused, but it hands out a session that can read why the
     * account is suspended and download its data. The owner is told about that one too, and the
     * alert survives the refusal that follows it.
     */
    @Test
    void aSuspendedCustomersSignInIsRefused_andTheOwnerIsStillTold() throws Exception {
        User user = newUser(true);
        user.setStatus("SUSPENDED");
        userRepository.save(user);

        signIn(user, PASSWORD, 403);

        assertThat(alertsFor(user.getId())).extracting(row -> row.get("channel")).containsExactly("EMAIL", "PUSH");
    }

    /** One alert per sign-in: a second sign-in is a second session and is told again. */
    @Test
    void everySignInIsTold_notOnlyTheFirst() throws Exception {
        User user = newUser(true);

        signIn(user, PASSWORD, 200);
        signIn(user, PASSWORD, 200);

        assertThat(alertsFor(user.getId())).hasSize(4);
        assertThat(alertsFor(user.getId())).extracting(row -> row.get("notification_key")).doesNotHaveDuplicates();
    }

    /**
     * Anyone can register with an address that is not theirs and leave it unverified. Their sign-ins
     * must not put mail in that stranger's inbox. Phones signed in to the account are still told.
     */
    @Test
    void anUnverifiedAddressGetsNoEmail_onlyThePush() throws Exception {
        User user = newUser(false);

        signIn(user, PASSWORD, 200);

        assertThat(alertsFor(user.getId())).extracting(row -> row.get("channel")).containsExactly("PUSH");
    }

    @Test
    void aWrongPasswordTellsNobody() throws Exception {
        User user = newUser(true);

        signIn(user, "NotThePassword1", 401);

        assertThat(alertsFor(user.getId())).isEmpty();
    }

    /** Signing up creates the account; there is no earlier owner to tell. */
    @Test
    void signingUpTellsNobody() throws Exception {
        String email = "sign-in-alert-signup-" + UUID.randomUUID() + "@example.com";
        String phone = "+919" + String.format("%09d", ThreadLocalRandom.current().nextInt(1_000_000_000));

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", email, "password", PASSWORD, "fullName", "Sign Up", "phoneNumber", phone))))
                .andExpect(status().is2xxSuccessful());

        UUID userId = userRepository.findByEmailIgnoreCaseAndAccountScope(email, User.SCOPE_USER).orElseThrow().getId();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM refresh_tokens WHERE user_id = ?", Long.class, userId))
                .as("signing up did create a session, so this is not passing for want of one")
                .isEqualTo(1L);
        assertThat(alertsFor(userId)).isEmpty();
    }

    /** A refresh continues a session. It is not a sign-in and happens every few minutes. */
    @Test
    void refreshingASessionTellsNobody() throws Exception {
        User user = newUser(true);
        String refreshToken = refreshTokenService.issue(user.getId()).rawToken();

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("refreshToken", refreshToken))))
                .andExpect(status().isOk());

        assertThat(alertsFor(user.getId())).isEmpty();
    }
}
