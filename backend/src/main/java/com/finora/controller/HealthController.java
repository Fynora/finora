package com.finora.controller;

import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.boot.actuate.health.HealthComponent;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.Status;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * The public liveness endpoint, on the application port.
 *
 * <p><b>Why this exists.</b> {@code management.server.port} moves the whole actuator context onto a
 * private port, so {@code /actuator/health} is no longer reachable from the internet. That is the
 * point for {@code /actuator/prometheus}, but {@code /actuator/health} was never the sensitive one
 * and four separate things were watching it:
 *
 * <ul>
 *   <li>Better Stack's external uptime monitor ({@code ops/monitoring/README.md})</li>
 *   <li>Railway's own deploy healthcheck ({@code backend/railway.json}) -- a deploy that cannot
 *       reach it is rolled back, so losing this path breaks deploys, not just monitoring</li>
 *   <li>{@code docker-compose.yml}'s container healthcheck</li>
 *   <li>four CI readiness probes that wait for the backend before running e2e suites</li>
 * </ul>
 *
 * <p>All four now point here instead.
 *
 * <p><b>Why it delegates rather than returning a constant.</b> A hardcoded {@code 200 OK} would be
 * trivially simpler and would report the service healthy straight through a database outage --
 * strictly worse than the monitoring it replaced. {@link HealthEndpoint} is the same aggregate
 * {@code /actuator/health} served, with the same contributors and the same
 * {@code management.health.redis.enabled: false} carve-out, so what an external monitor sees does
 * not change.
 *
 * <p><b>Why the body is rebuilt instead of returned.</b> Better Stack content-matches on
 * {@code "status":"UP"}, so the shape has to survive. Reading only {@link Status} and re-emitting
 * it guarantees no contributor detail can ever reach an unauthenticated caller, independently of
 * whether {@code management.endpoint.health.show-details} is ever changed from {@code never}.
 *
 * <p><b>The status-code mapping is written out</b> rather than delegated to Spring's
 * {@code HttpCodeStatusMapper}: that bean's availability depends on actuator's web
 * auto-configuration, which now lives in the management child context. UP and UNKNOWN answer 200,
 * everything else 503 -- the same mapping Spring Boot applies by default.
 *
 * <p>{@code @Hidden} keeps it out of the OpenAPI document. It is infrastructure, not product API,
 * and publishing it would regenerate all three API clients for an endpoint none of them calls.
 */
@Hidden
@RestController
public class HealthController {

    private final HealthEndpoint healthEndpoint;

    public HealthController(HealthEndpoint healthEndpoint) {
        this.healthEndpoint = healthEndpoint;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        HealthComponent health = healthEndpoint.health();
        Status status = health.getStatus();
        HttpStatus code = (Status.UP.equals(status) || Status.UNKNOWN.equals(status))
                ? HttpStatus.OK
                : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(code).body(Map.of("status", status.getCode()));
    }
}
