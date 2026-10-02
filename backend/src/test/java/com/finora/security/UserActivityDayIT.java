package com.finora.security;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.User;
import com.finora.entity.UserActivityDay;
import com.finora.repository.UserActivityDayRepository;
import com.finora.repository.UserRepository;
import com.finora.service.RefreshTokenService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End to end through the real filter chain: a signed-in user's request lands one
 * {@code user_activity_days} row for today. The unit test covers the interceptor's own rules; this
 * one proves the seams it depends on -- that the real JwtAuthFilter/AuthorizationService grant a
 * user-portal account the authority the interceptor looks for, that WebMvcConfig actually registers
 * it on API routes, and that the V249 table and native insert agree.
 */
class UserActivityDayIT extends AbstractIntegrationTest {

    private static final String PROTECTED = "/api/v1/users/me/access";

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenService refreshTokenService;
    @Autowired private UserActivityDayRepository userActivityDayRepository;

    private User newUser() {
        User user = new User();
        user.setEmail("activity-day-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Activity Day IT");
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    private String accessTokenFor(User user) {
        RefreshTokenService.IssuedToken issued = refreshTokenService.issue(user.getId());
        return jwtService.generateToken(user.getId(), user.getEmail(), issued.sessionId(), user.getAccountScope());
    }

    private int call(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        if (accessToken != null) headers.setBearerAuth(accessToken);
        ResponseEntity<String> response = restTemplate.exchange(
                PROTECTED, HttpMethod.GET, new HttpEntity<>(headers), String.class);
        return response.getStatusCode().value();
    }

    @Test
    void aSignedInUsersRequestsRecordExactlyOneRowForToday() {
        User user = newUser();
        String token = accessTokenFor(user);

        LocalDate before = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        assertThat(call(token)).isEqualTo(200);
        assertThat(call(token)).isEqualTo(200);
        assertThat(call(token)).isEqualTo(200);
        LocalDate after = LocalDate.now(ZoneId.of("Asia/Kolkata"));

        List<UserActivityDay> rows = userActivityDayRepository.findByUserIdOrderByActivityDateAsc(user.getId());
        // Three requests, one day: one row. The date is today in India; `before`/`after` only
        // differ if the test happened to straddle Indian midnight.
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getActivityDate()).isBetween(before, after);
    }

    @Test
    void anUnauthenticatedRequestRecordsNothing() {
        User user = newUser();

        assertThat(call(null)).isEqualTo(401);

        assertThat(userActivityDayRepository.findByUserIdOrderByActivityDateAsc(user.getId())).isEmpty();
    }

    @Test
    void recordingTheSameDayTwiceDirectlyIsANoOp() {
        // The cross-instance case: a second backend instance has its own in-memory "already
        // recorded" map, so it will issue the insert again. That must collapse, not throw.
        User user = newUser();
        LocalDate day = LocalDate.of(2026, 9, 30);

        userActivityDayRepository.recordDay(user.getId(), day);
        userActivityDayRepository.recordDay(user.getId(), day);
        userActivityDayRepository.recordDay(user.getId(), day.plusDays(1));

        assertThat(userActivityDayRepository.findByUserIdOrderByActivityDateAsc(user.getId()))
                .extracting(UserActivityDay::getActivityDate)
                .containsExactly(day, day.plusDays(1));
    }
}
