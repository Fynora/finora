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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the worker metrics are actually scrapeable, rather than merely registered.
 *
 * <p>The distinction matters and has bitten this repository before: a meter that exists in a
 * registry nothing exports is exactly as useful as no meter at all, and looks identical in a unit
 * test. Registering counters was the easy half; being able to draw a dashboard from them is the
 * half that needed a Prometheus registry and an exposure change, and this is what verifies it.
 *
 * <h2>The endpoint is private, not authenticated</h2>
 *
 * <p>This used to assert the scrape was unreachable anonymously, because it sat on the public port
 * behind {@code anyRequest().authenticated()}. It no longer does: actuator moved to
 * {@code management.server.port}, which no Railway domain routes to, and the scrape is served there
 * without a credential so Prometheus can read it on the private network.
 *
 * <p>The property that assertion protected -- "the scrape is not readable from the internet" -- did
 * not go away, it changed shape, and {@code ManagementPortIsolationIT} now owns it in both
 * directions. {@code ManagementPortSeparationGuard} refuses to boot if the separation is lost.
 */
class WorkerMetricsExportIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private WorkerObservability observability;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;

    private HttpHeaders adminBearer() {
        User user = new User();
        user.setEmail("metrics-export-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Metrics Export IT User");
        user.setRole("ADMIN");
        // An admin is an ADMIN-PORTAL account. Since V52 the scope is what decides whether a
        // role's permissions are granted at all (AuthorizationService), so a fixture setting
        // only the role builds a state the application refuses to create -- RoleService
        // .requireScopeCanHold rejects attaching a permission-bearing role to a USER-scope row.
        user.setAccountScope(User.SCOPE_ADMIN);
        user.setPhoneVerified(true);
        user = userRepository.save(user);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestSessions.accessTokenFor(jwtService, refreshTokens, user));
        return headers;
    }

    private String scrape() {
        // Anonymous, and on the management port: the scrape no longer carries a credential.
        // See ManagementPortIsolationIT for why that is safe.
        ResponseEntity<String> response =
                restTemplate.getForEntity(actuatorUrl("prometheus"), String.class);
        assertThat(response.getStatusCode().is2xxSuccessful())
                .as("the scrape must work; without it every worker meter is invisible")
                .isTrue();
        return response.getBody();
    }

    @Test
    void workerMetricsAppearInTheScrape() {
        try (WorkerExecution execution = observability.begin("test-worker", "test-job")) {
            execution.claimed(1);
            execution.started(UUID.randomUUID(), null);
            execution.completed(UUID.randomUUID());
            execution.retryScheduled(UUID.randomUUID(), 1);
            execution.deadLettered(UUID.randomUUID(), 3, new IllegalStateException("gave up"));
            execution.recovered(2);
        }

        String body = scrape();

        // Prometheus renders dots as underscores, so these are the names a dashboard query uses.
        assertThat(body)
                .contains("finora_worker_executions")
                .contains("finora_worker_completed")
                .contains("finora_worker_retries")
                .contains("finora_worker_dead_letters")
                .contains("finora_worker_recovered")
                .contains("finora_worker_failures")
                .contains("finora_worker_duration");
    }

    @Test
    void metersCarryWorkerAndJobKindLabels_soOneQueryCoversEveryWorker() {
        try (WorkerExecution execution = observability.begin("labelled-worker", "labelled-job")) {
            execution.completed(UUID.randomUUID());
        }

        assertThat(scrape())
                .contains("worker=\"labelled-worker\"")
                .contains("jobKind=\"labelled-job\"");
    }

    @Test
    void theRealMerchantLearningQueueDepthGaugeIsExported() {
        // Registered by MerchantLearningEventWorker's constructor against a real repository count,
        // so this also proves the gauge's supplier does not throw on the scrape path.
        assertThat(scrape()).contains("finora_worker_queue_depth");
    }

    @Test
    void oldestPendingAgeIsExportedUnderTheNameTheDashboardQueries() {
        // baseUnit("seconds") becomes part of the exported name -- the exact translation
        // check-dashboard-metrics.py exists to police. Asserted against a real scrape so the
        // dashboard query and the emitted series are proven to match rather than assumed to.
        assertThat(scrape()).contains("finora_worker_oldest_pending_age_seconds");
    }

    @Test
    void everyMeterIsStampedWithTheEnvironment_soOnePrometheusCanHoldMoreThanOneDeployment() {
        try (WorkerExecution execution = observability.begin("env-worker", "env-job")) {
            execution.completed(UUID.randomUUID());
        }

        assertThat(scrape()).contains("environment=\"");
    }

    @Test
    void theRiskierActuatorEndpointsStayUnexposed() {
        // The exposure list is one property away from including env, configprops, heapdump and
        // threaddump. Those leak configuration and memory contents, which is a different risk class
        // from counters -- asserted rather than trusted.
        for (String forbidden : new String[]{"env", "configprops", "beans", "threaddump", "loggers"}) {
            // On the management port, with an admin token: so a non-2xx here means "not in the
            // exposure list", not merely "not authenticated". Asking on the application port would
            // pass vacuously now that nothing under /actuator is mapped there at all.
            assertThat(restTemplate.exchange(actuatorUrl(forbidden), HttpMethod.GET,
                    new HttpEntity<>(adminBearer()), String.class).getStatusCode())
                    .as("/actuator/%s must not be exposed, even to an admin", forbidden)
                    .isEqualTo(HttpStatus.NOT_FOUND);
        }
    }
}
