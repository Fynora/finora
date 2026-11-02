package com.finora.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.User;
import com.finora.notification.domain.NotificationCategory;
import com.finora.notification.domain.NotificationChannel;
import com.finora.notification.domain.NotificationPreferenceChange;
import com.finora.notification.repository.NotificationPreferenceChangeRepository;
import com.finora.notification.repository.NotificationPreferenceRepository;
import com.finora.repository.RefreshTokenRepository;
import com.finora.repository.UserRepository;
import com.finora.security.JwtService;
import com.finora.service.AccountPurgeSweepService;
import com.finora.support.ClientIdentity;
import com.finora.testsupport.TestSessions;
import java.time.Instant;
import java.util.List;
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
 * V306: every change to a notification switch is recorded -- when, from which app, and which
 * wording the app showed -- so Fynora can show that a user turned "Offers and news" on (DPDP Act
 * s.6(10)). Real HTTP and real Postgres, because the client comes from a request header and the
 * record has to commit with the change itself.
 */
class NotificationPreferenceHistoryIT extends AbstractIntegrationTest {

    private static final String WITH_MARKETING =
            "/api/v1/notification-preferences?categories=FINANCIAL,REMINDERS,MARKETING";

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    @Autowired private NotificationPreferenceRepository preferences;
    @Autowired private NotificationPreferenceChangeRepository history;
    @Autowired private AccountPurgeSweepService purge;

    private User createUser(String scope) {
        User user = new User();
        user.setEmail("notification-history-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Notification History IT User");
        user.setPhoneVerified(true);
        user.setAccountScope(scope);
        return userRepository.save(user);
    }

    private ResponseEntity<String> put(User user, String platform, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestSessions.accessTokenFor(jwtService, refreshTokens, user));
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (platform != null) {
            headers.set(ClientIdentity.PLATFORM_HEADER, platform);
        }
        return restTemplate.exchange(WITH_MARKETING, HttpMethod.PUT, new HttpEntity<>(body, headers), String.class);
    }

    private static String body(String category, String channel, boolean enabled, String noticeVersion) {
        return noticeVersion == null
                ? """
                  {"category": "%s", "channel": "%s", "enabled": %s}
                  """.formatted(category, channel, enabled)
                : """
                  {"category": "%s", "channel": "%s", "enabled": %s, "noticeVersion": "%s"}
                  """.formatted(category, channel, enabled, noticeVersion);
    }

    @Test
    void turningOffersAndNewsOnAndOff_recordsEachChange_withTheAppAndTheWording() {
        User user = createUser(User.SCOPE_USER);
        Instant before = Instant.now();

        assertThat(put(user, "MOBILE_IOS", body("MARKETING", "PUSH", true, "offers-news-2026-11")).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(put(user, "WEB", body("MARKETING", "PUSH", false, "offers-news-2026-11")).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        Instant after = Instant.now();

        List<NotificationPreferenceChange> rows = history.findByUserIdOrderByRecordedAtAsc(user.getId());
        assertThat(rows).hasSize(2);
        NotificationPreferenceChange on = rows.get(0);
        assertThat(on.getCategory()).isEqualTo(NotificationCategory.MARKETING);
        assertThat(on.getChannel()).isEqualTo(NotificationChannel.PUSH);
        assertThat(on.isEnabled()).isTrue();
        assertThat(on.getClient()).isEqualTo("MOBILE_IOS");
        assertThat(on.getNoticeVersion()).isEqualTo("offers-news-2026-11");
        assertThat(on.getMethod()).isEqualTo(NotificationPreferenceChange.METHOD_SWITCH);
        assertThat(on.getRecordedAt()).isBetween(before, after);
        NotificationPreferenceChange off = rows.get(1);
        assertThat(off.isEnabled()).isFalse();
        assertThat(off.getClient()).isEqualTo("WEB");
        assertThat(off.getRecordedAt()).isAfterOrEqualTo(on.getRecordedAt());
        // The current setting is still one row; the history is beside it, not instead of it.
        assertThat(preferences.findByUserId(user.getId())).singleElement()
                .satisfies(p -> assertThat(p.isEnabled()).isFalse());
    }

    @Test
    void anAppThatSendsNoWording_stillHasItsChangeRecorded() {
        User user = createUser(User.SCOPE_USER);

        put(user, null, body("FINANCIAL", "EMAIL", false, null));

        assertThat(history.findByUserIdOrderByRecordedAtAsc(user.getId())).singleElement().satisfies(row -> {
            assertThat(row.getCategory()).isEqualTo(NotificationCategory.FINANCIAL);
            assertThat(row.getChannel()).isEqualTo(NotificationChannel.EMAIL);
            assertThat(row.isEnabled()).isFalse();
            // No header reads as WEB (ClientIdentity); no wording means none was claimed.
            assertThat(row.getClient()).isEqualTo("WEB");
            assertThat(row.getNoticeVersion()).isNull();
        });
    }

    @Test
    void aRefusedChange_recordsNothing() {
        User user = createUser(User.SCOPE_USER);

        assertThat(put(user, "MOBILE_ANDROID", body("MARKETING", "EMAIL", true, "offers-news-2026-11")).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(put(user, "MOBILE_ANDROID", body("MARKETING", "PUSH", true, "not a version!")).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(put(user, "MOBILE_ANDROID", body("MARKETING", "PUSH", true, "v".repeat(33))).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        assertThat(history.findByUserIdOrderByRecordedAtAsc(user.getId())).isEmpty();
        assertThat(preferences.findByUserId(user.getId())).isEmpty();
    }

    @Test
    void theRecordIsKeptWhenTheAccountIsDeleted_whileTheSettingItselfGoes() {
        User user = createUser(User.SCOPE_USER);
        User admin = createUser(User.SCOPE_ADMIN);
        put(user, "MOBILE_ANDROID", body("MARKETING", "PUSH", true, "offers-news-2026-11"));

        purge.adminPurge(user.getId(), admin.getId());

        assertThat(userRepository.findById(user.getId()).orElseThrow().isDeleted()).isTrue();
        assertThat(preferences.findByUserId(user.getId())).isEmpty();
        assertThat(history.findByUserIdOrderByRecordedAtAsc(user.getId())).singleElement()
                .satisfies(row -> assertThat(row.isEnabled()).isTrue());
    }
}
