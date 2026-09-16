package com.finora.observability;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.User;
import com.finora.repository.RefreshTokenRepository;
import com.finora.repository.UserRepository;
import com.finora.security.JwtService;
import com.finora.testsupport.TestSessions;
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
 * Proves the four auth counters are actually scrapeable -- the same distinction
 * {@link ReconciliationMetricsExportIT} exists to prove for reconciliation, and
 * {@link WorkerMetricsExportIT} for workers: a meter registered but never exported is
 * indistinguishable from no meter at all in a mocked unit test.
 *
 * <p>Calls {@link AuthMetrics} directly rather than driving a real login/refresh cycle through
 * the HTTP layer -- this test's job is "does incrementing through this component reach the
 * scrape," not "does {@code RefreshTokenService} correctly decide when to call it," which
 * {@code RefreshTokenSessionLimitsTest} already covers.
 */
class AuthMetricsExportIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private AuthMetrics authMetrics;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;

    private HttpHeaders adminBearer() {
        User user = new User();
        user.setEmail("auth-metrics-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Auth Metrics IT User");
        user.setRole("ADMIN");
        user.setAccountScope(User.SCOPE_ADMIN);
        user.setPhoneVerified(true);
        user = userRepository.save(user);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestSessions.accessTokenFor(jwtService, refreshTokens, user));
        return headers;
    }

    private String scrape() {
        ResponseEntity<String> response = restTemplate.exchange(
                "/actuator/prometheus", HttpMethod.GET, new HttpEntity<>(adminBearer()), String.class);
        assertThat(response.getStatusCode().is2xxSuccessful())
                .as("an authenticated scrape must work; without it these counters are invisible")
                .isTrue();
        return response.getBody();
    }

    @Test
    void loginSuccessAppearsInTheScrape() {
        authMetrics.loginSucceeded();

        // Prometheus renders dots as underscores and appends _total to a counter -- the exact
        // translation scripts/check-dashboard-metrics.py exists to police for any future Grafana
        // panel built against this metric.
        assertThat(scrape()).contains("finora_auth_login_success_total");
    }

    @Test
    void refreshSuccessAppearsInTheScrape() {
        authMetrics.refreshSucceeded();

        assertThat(scrape()).contains("finora_auth_refresh_success_total");
    }

    @Test
    void refreshExpiredIdleAppearsInTheScrape() {
        authMetrics.refreshExpiredIdle();

        assertThat(scrape()).contains("finora_auth_refresh_expired_idle_total");
    }

    @Test
    void refreshExpiredAbsoluteAppearsInTheScrape() {
        authMetrics.refreshExpiredAbsolute();

        assertThat(scrape()).contains("finora_auth_refresh_expired_absolute_total");
    }
}
