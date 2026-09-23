package com.finora.controller;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.Status;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The failure paths of the public health endpoint.
 *
 * <p>{@code ManagementPortIsolationIT} proves the healthy case against a real server, and that is
 * the easy half -- a controller hardcoded to {@code return "UP"} would pass it identically. This
 * covers the half that actually justifies delegating to {@link HealthEndpoint}: that a sick system
 * is reported as sick.
 *
 * <p>It matters more than the usual "unhappy path" test, because of who is listening. Railway rolls
 * back a deploy that cannot get a 200 here, and Better Stack decides whether to wake someone. A
 * health endpoint that answers 200 through a database outage does not degrade monitoring, it
 * inverts it -- the worse the outage, the more confidently it reports success.
 */
class HealthControllerTest {

    private final HealthEndpoint healthEndpoint = mock(HealthEndpoint.class);
    private final HealthController controller = new HealthController(healthEndpoint);

    private ResponseEntity<Map<String, String>> healthWhenAggregateIs(Status status) {
        when(healthEndpoint.health()).thenReturn(Health.status(status).build());
        return controller.health();
    }

    @Test
    void upIsReportedAsTwoHundred() {
        ResponseEntity<Map<String, String>> response = healthWhenAggregateIs(Status.UP);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(Map.of("status", "UP"));
    }

    @Test
    void downIsReportedAsServiceUnavailable() {
        ResponseEntity<Map<String, String>> response = healthWhenAggregateIs(Status.DOWN);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).isEqualTo(Map.of("status", "DOWN"));
    }

    @Test
    void outOfServiceIsReportedAsServiceUnavailable() {
        // Distinct from DOWN in Spring's model and reachable through a custom indicator, so it is
        // asserted rather than assumed to fall through the same branch.
        assertThat(healthWhenAggregateIs(Status.OUT_OF_SERVICE).getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void unknownIsReportedAsTwoHundred() {
        // Matches Spring Boot's own default mapping. UNKNOWN means "no contributor had an opinion",
        // which is not evidence of a fault -- answering 503 would roll back healthy deploys.
        assertThat(healthWhenAggregateIs(Status.UNKNOWN).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void aCustomStatusIsTreatedAsUnhealthyRatherThanHealthy() {
        // The mapping is an allowlist: anything that is not explicitly UP or UNKNOWN answers 503.
        // Written down because the opposite default -- unrecognised means fine -- is the one that
        // hides an outage, and a new Status is exactly the kind of thing added without revisiting
        // this file.
        assertThat(healthWhenAggregateIs(new Status("DEGRADED")).getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void noContributorDetailReachesTheBody() {
        // show-details is `never` today, but this endpoint is unauthenticated and internet-facing,
        // so it must not depend on that setting staying put. The controller reads only the status,
        // and this proves a fully-detailed aggregate still renders as a bare status.
        when(healthEndpoint.health()).thenReturn(Health.down()
                .withDetail("database", "Connection refused: localhost:5432")
                .withDetail("diskSpace", "free=12MB")
                .build());

        assertThat(controller.health().getBody()).isEqualTo(Map.of("status", "DOWN"));
    }
}
