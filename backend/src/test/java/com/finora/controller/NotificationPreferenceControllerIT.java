package com.finora.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
import com.finora.entity.User;
import com.finora.notification.api.NotificationPreferenceResolver;
import com.finora.notification.domain.NotificationCategory;
import com.finora.notification.domain.NotificationChannel;
import com.finora.notification.repository.NotificationPreferenceRepository;
import com.finora.repository.RefreshTokenRepository;
import com.finora.repository.UserRepository;
import com.finora.security.JwtService;
import com.finora.testsupport.TestSessions;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * The opt-out seam end to end: a toggle sent over HTTP lands in notification_preferences, and the
 * resolver the notification pipeline actually consults then answers accordingly. Without that
 * last step a settings switch could save perfectly and still change nothing about what is sent.
 */
class NotificationPreferenceControllerIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    @Autowired private NotificationPreferenceResolver resolver;
    @Autowired private NotificationPreferenceRepository preferenceRepository;
    private final ObjectMapper mapper = new ObjectMapper();

    private User createUser() {
        User user = new User();
        user.setEmail("notification-prefs-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Notification Prefs IT User");
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    private HttpHeaders bearerFor(User user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestSessions.accessTokenFor(jwtService, refreshTokens, user));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private ResponseEntity<String> put(User user, String category, String channel, boolean enabled) {
        String body = """
                {"category": "%s", "channel": "%s", "enabled": %s}
                """.formatted(category, channel, enabled);
        return restTemplate.exchange("/api/v1/notification-preferences", HttpMethod.PUT,
                new HttpEntity<>(body, bearerFor(user)), String.class);
    }

    private boolean enabledIn(JsonNode data, String channel) {
        for (JsonNode pref : data) {
            if (pref.get("channel").asText().equals(channel)) {
                assertThat(pref.get("category").asText()).isEqualTo("FINANCIAL");
                return pref.get("enabled").asBoolean();
            }
        }
        throw new AssertionError("no " + channel + " preference in " + data);
    }

    @Test
    void list_defaultsFinancialEmailAndPushToOn_forAUserWhoNeverChoseAnything() throws Exception {
        User user = createUser();

        ResponseEntity<String> response = restTemplate.exchange("/api/v1/notification-preferences",
                HttpMethod.GET, new HttpEntity<>(bearerFor(user)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = mapper.readTree(response.getBody()).get("data");
        assertThat(data).hasSize(2);
        assertThat(enabledIn(data, "EMAIL")).isTrue();
        assertThat(enabledIn(data, "PUSH")).isTrue();
    }

    @Test
    void turningFinancialEmailOff_isStoredAndHonouredByTheResolver_andOnlyForThatChannel() throws Exception {
        User user = createUser();

        ResponseEntity<String> response = put(user, "FINANCIAL", "EMAIL", false);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = mapper.readTree(response.getBody()).get("data");
        assertThat(enabledIn(data, "EMAIL")).isFalse();
        assertThat(enabledIn(data, "PUSH")).isTrue();
        assertThat(resolver.isEnabled(user.getId(), NotificationCategory.FINANCIAL, NotificationChannel.EMAIL)).isFalse();
        assertThat(resolver.isEnabled(user.getId(), NotificationCategory.FINANCIAL, NotificationChannel.PUSH)).isTrue();
        // SECURITY is forced on whatever FINANCIAL says.
        assertThat(resolver.isEnabled(user.getId(), NotificationCategory.SECURITY, NotificationChannel.EMAIL)).isTrue();
    }

    @Test
    void togglingTheSamePreferenceTwice_updatesOneRowInsteadOfCollidingOnTheUniqueConstraint() throws Exception {
        User user = createUser();

        assertThat(put(user, "FINANCIAL", "EMAIL", false).getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<String> second = put(user, "FINANCIAL", "EMAIL", true);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(enabledIn(mapper.readTree(second.getBody()).get("data"), "EMAIL")).isTrue();
        assertThat(preferenceRepository.findByUserId(user.getId())).hasSize(1);
        assertThat(resolver.isEnabled(user.getId(), NotificationCategory.FINANCIAL, NotificationChannel.EMAIL)).isTrue();
    }

    @Test
    void oneUsersChoice_doesNotChangeAnotherUsers() {
        User alice = createUser();
        User bob = createUser();

        put(alice, "FINANCIAL", "EMAIL", false);

        assertThat(resolver.isEnabled(bob.getId(), NotificationCategory.FINANCIAL, NotificationChannel.EMAIL)).isTrue();
    }

    @Test
    void securityCannotBeSwitchedOff() {
        User user = createUser();

        ResponseEntity<String> response = put(user, "SECURITY", "EMAIL", false);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(preferenceRepository.findByUserId(user.getId())).isEmpty();
    }

    @Test
    void smsIsNotAChannelFinancialNotificationsUse_soItIsRejected() {
        User user = createUser();

        assertThat(put(user, "FINANCIAL", "SMS", false).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void aMissingEnabledFlag_isRejectedRatherThanStoredAsFalse() {
        User user = createUser();
        String body = """
                {"category": "FINANCIAL", "channel": "EMAIL"}
                """;

        ResponseEntity<String> response = restTemplate.exchange("/api/v1/notification-preferences",
                HttpMethod.PUT, new HttpEntity<>(body, bearerFor(user)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(preferenceRepository.findByUserId(user.getId())).isEmpty();
    }

    @Test
    void anonymousCaller_isRejected() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> response = restTemplate.exchange("/api/v1/notification-preferences",
                HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
