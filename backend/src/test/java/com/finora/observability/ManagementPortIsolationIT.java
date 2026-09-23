package com.finora.observability;

import com.finora.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Owns the security property that makes unauthenticated scraping safe.
 *
 * <h2>What changed, and why this class exists</h2>
 *
 * <p>{@code /actuator/prometheus} used to be protected by authentication on the public port. That
 * was the right posture and it had one unavoidable consequence: a scraper needed a credential, and
 * the only credentials this system issues are user access tokens that expire in fifteen minutes.
 * There is no service principal to mint one for. So the endpoint was secure and unscrapeable at the
 * same time, which is why no production dashboard existed.
 *
 * <p>The fix was to make the endpoint unreachable by network position instead of by password:
 * actuator now listens on {@code management.server.port}, Railway routes no domain to that port
 * (both public domains are pinned to the application port), and the endpoint is {@code permitAll}
 * so a Prometheus on the private network can read it with no credential at all.
 *
 * <p>That trade is only sound while <em>both</em> halves hold, and each half fails silently on its
 * own:
 *
 * <ul>
 *   <li>If actuator is ever mapped on the application port again, {@code permitAll} publishes queue
 *       depths, error rates and JVM internals to the internet. Nothing throws; the scrape simply
 *       starts answering strangers.</li>
 *   <li>If the management listener stops serving the scrape, every dashboard quietly goes flat --
 *       and an empty panel is indistinguishable from a healthy, idle system.</li>
 * </ul>
 *
 * <p>So both are asserted here, against a running server, rather than inferred from configuration.
 * {@code ManagementPortSeparationGuard} covers the third case, where no separate management
 * listener is created at all.
 *
 * <h2>A note on the 404s</h2>
 *
 * <p>The application port answers 404, not 401, for actuator paths -- the endpoints are not mapped
 * in that context at all, so there is nothing behind the security filter to reach. Asserting "not
 * 2xx" would also pass if the path were merely authenticated, which is a materially weaker
 * property, so these assert the status code exactly.
 */
class ManagementPortIsolationIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;

    @LocalServerPort private int serverPort;

    @Test
    void theScrapeIsNotMappedOnTheApplicationPortAtAll() {
        ResponseEntity<String> response =
                restTemplate.getForEntity("/actuator/prometheus", String.class);

        assertThat(response.getStatusCode())
                .as("permitAll on /actuator/prometheus is only safe while the endpoint is absent "
                        + "from the public port; anything routable here publishes the scrape")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void noActuatorEndpointIsReachableOnTheApplicationPort() {
        // Not just the scrape: health moved too, and the riskier endpoints must not become reachable
        // on the public port through some future exposure change either.
        //
        // These answer with two different codes, and the difference is worth knowing. The two paths
        // SecurityConfig permits answer 404 -- security lets them through and there is no handler
        // behind it. The rest answer 401, rejected by anyRequest().authenticated() before dispatch
        // ever happens. Unreachable either way, so this asserts the property they share; the 404
        // half, which is the one carrying the security argument, is asserted exactly in
        // theScrapeIsNotMappedOnTheApplicationPortAtAll above.
        for (String path : new String[] {"health", "prometheus", "env", "configprops", "heapdump"}) {
            assertThat(restTemplate.getForEntity("/actuator/" + path, String.class)
                    .getStatusCode().is2xxSuccessful())
                    .as("/actuator/%s must not be reachable on the application port", path)
                    .isFalse();
        }
    }

    @Test
    void theScrapeIsReadableAnonymouslyOnTheManagementPort() {
        // The other half. Prometheus holds no credential, so if this ever needs one, collection
        // stops -- and stops invisibly, because a dashboard with no data looks like a quiet system.
        ResponseEntity<String> response =
                restTemplate.getForEntity(actuatorUrl("prometheus"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .as("a 200 with no meters in it would satisfy the status check and still be useless")
                .contains("jvm_memory_used_bytes");
    }

    @Test
    void theManagementPortIsNotTheApplicationPort() {
        // The premise every other assertion in this class rests on. Cheap to state, and it fails
        // with a clear message rather than as four confusing 404s if the two ever converge.
        assertThat(managementPort).isNotZero().isNotEqualTo(serverPort);
    }

    @Test
    void thePublicHealthEndpointRepliesInTheShapeTheUptimeMonitorMatchesOn() {
        // Better Stack content-matches on "status":"UP" and Railway's deploy healthcheck reads the
        // status code; a deploy that cannot get a 200 here is rolled back. Both are asserted
        // because either one alone would pass against a broken version of the other.
        ResponseEntity<String> response = restTemplate.getForEntity("/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo("{\"status\":\"UP\"}");
    }

    @Test
    void thePublicHealthEndpointLeaksNoComponentDetail() {
        // It is unauthenticated and internet-facing, so it must stay a status and nothing else --
        // no component names, no database or disk detail. This holds independently of
        // management.endpoint.health.show-details, which HealthController deliberately does not
        // depend on.
        String body = restTemplate.getForEntity("/health", String.class).getBody();

        assertThat(body).doesNotContain("components").doesNotContain("db").doesNotContain("disk");
    }
}
