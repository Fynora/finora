package com.finora.observability;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.User;
import com.finora.repository.UserRepository;
import com.finora.security.JwtService;
import com.finora.service.RefreshTokenService;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End to end through the real filter chain: a signed-in user's requests are counted, an anonymous
 * request is not, and nothing is ever refused. The unit tests cover the counting rules; this proves
 * the seams they depend on: that {@code WebMvcConfig} really registers the interceptor on API
 * routes, and that the real JWT filter hands it a principal whose name is the user id and whose
 * authorities say which portal it belongs to.
 *
 * <p>Read as a change in the exported histogram, not as an absolute, because other tests in the
 * same JVM share the application context and its meter registry.
 */
class UserRequestLoadIT extends AbstractIntegrationTest {

    private static final String PROTECTED = "/api/v1/users/me/access";

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenService refreshTokenService;
    @Autowired private UserRequestLoad load;
    @Autowired private MeterRegistry registry;

    private String accessTokenFor(User user) {
        RefreshTokenService.IssuedToken issued = refreshTokenService.issue(user.getId());
        return jwtService.generateToken(user.getId(), user.getEmail(), issued.sessionId(), user.getAccountScope());
    }

    private User newUser() {
        User user = new User();
        user.setEmail("user-load-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("User Load IT");
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    private int call(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        if (accessToken != null) headers.setBearerAuth(accessToken);
        ResponseEntity<String> response = restTemplate.exchange(
                PROTECTED, HttpMethod.GET, new HttpEntity<>(headers), String.class);
        return response.getStatusCode().value();
    }

    private double totalRequests() {
        DistributionSummary summary = registry.find("finora.user_load.requests_per_minute").tag("portal", "user").summary();
        return summary == null ? 0 : summary.totalAmount();
    }

    private long userMinutes() {
        DistributionSummary summary = registry.find("finora.user_load.requests_per_minute").tag("portal", "user").summary();
        return summary == null ? 0 : summary.count();
    }

    @Test
    void aSignedInUsersRequestsAreCountedAsOneUserMinuteAndNoneIsRefused() {
        String token = accessTokenFor(newUser());
        load.flushNow(); // start from a clean minute
        double requestsBefore = totalRequests();
        long minutesBefore = userMinutes();

        for (int i = 0; i < 5; i++) assertThat(call(token)).isEqualTo(200);
        load.flushNow();

        assertThat(totalRequests() - requestsBefore).as("five requests counted").isEqualTo(5.0);
        assertThat(userMinutes() - minutesBefore).as("by one user, in one minute").isEqualTo(1);
    }

    @Test
    void twoUsersAreTwoUserMinutes() {
        String first = accessTokenFor(newUser());
        String second = accessTokenFor(newUser());
        load.flushNow();
        long minutesBefore = userMinutes();

        call(first);
        call(second);
        call(second);
        load.flushNow();

        assertThat(userMinutes() - minutesBefore).isEqualTo(2);
    }

    @Test
    void anUnauthenticatedRequestIsRefusedAsBeforeAndCountsNothing() {
        load.flushNow();
        double requestsBefore = totalRequests();

        assertThat(call(null)).isEqualTo(401);
        load.flushNow();

        assertThat(totalRequests() - requestsBefore).isEqualTo(0.0);
    }

    @Test
    void thePublishedMetricsCarryNoUserIdentifier() {
        User user = newUser();
        call(accessTokenFor(user));
        load.flushNow();

        var meters = registry.getMeters().stream()
                .filter(m -> m.getId().getName().startsWith("finora.user_load")).toList();
        assertThat(meters).as("the measurement is registered").isNotEmpty();
        meters.forEach(m -> m.getId().getTags().forEach(tag -> {
            // The registry adds its own common tags (such as the environment) to every meter; the
            // only tag this feature adds is the bounded portal name.
            assertThat(tag.getValue()).as("tag %s of %s", tag.getKey(), m.getId().getName())
                    .doesNotContain(user.getId().toString())
                    .doesNotContain(user.getEmail())
                    .doesNotMatch("(?i).*[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}.*");
            if (tag.getKey().equals("portal")) assertThat(tag.getValue()).isIn("user", "admin", "other");
        }));
    }
}
