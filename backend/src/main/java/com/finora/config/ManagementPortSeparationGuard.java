package com.finora.config;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Refuses to finish starting if the actuator context is not on its own port.
 *
 * <h2>What this is protecting</h2>
 *
 * <p>{@code SecurityConfig} grants {@code permitAll} to {@code /actuator/prometheus}. That is only
 * safe because of a second, invisible fact: the endpoint is mapped exclusively in the management
 * child context, which listens on {@code management.server.port}, and no Railway domain routes to
 * that port. On the public port the path is not mapped at all and answers 404 --
 * {@code ManagementPortIsolationIT} asserts every half of that.
 *
 * <p>So the separation is a security control, not a layout preference. Remove it and the scrape --
 * queue depths, error rates, JVM internals, every counter this service publishes -- is served
 * anonymously to the internet, with no error, no failing test at runtime, and nothing in a log to
 * suggest anything changed. A single environment variable does it:
 * {@code MANAGEMENT_SERVER_PORT=8080}.
 *
 * <p>That is precisely the failure mode {@code ops/monitoring/prometheus.yml} and
 * {@code WorkerMetricsExportIT} have warned about from the other direction ("nobody should fix
 * scraping by adding /actuator/** to permitAll"). Moving the port did fix scraping -- which means
 * the warning now has to be enforced against the port instead of against the matcher.
 *
 * <h2>Why it checks bound ports, not properties</h2>
 *
 * <p>Reading {@code management.server.port} would be simpler and wrong twice over. Under
 * {@code @SpringBootTest(webEnvironment = RANDOM_PORT)} both properties are {@code 0}, so a
 * property comparison reports a collision in every integration test. And when the property is
 * absent entirely -- the genuinely dangerous case, because Spring Boot then serves actuator from
 * the main context -- there is no value to compare at all.
 *
 * <p>Bound ports answer both: a separate management server raises its own
 * {@link WebServerInitializedEvent} under the {@code management} namespace, so its absence is the
 * signal, and the two random ports in a test are really different.
 *
 * <p>Tests with {@code webEnvironment = MOCK} start no web server, raise no event, and are skipped
 * -- there is no listening socket for anyone to reach.
 */
@Component
public class ManagementPortSeparationGuard {

    private static final String MANAGEMENT_NAMESPACE = "management";

    private Integer applicationPort;
    private Integer managementPort;

    @EventListener
    public void onWebServerInitialized(WebServerInitializedEvent event) {
        if (MANAGEMENT_NAMESPACE.equals(event.getApplicationContext().getServerNamespace())) {
            managementPort = event.getWebServer().getPort();
        } else {
            applicationPort = event.getWebServer().getPort();
        }
    }

    @EventListener
    public void onApplicationReady(ApplicationReadyEvent event) {
        if (applicationPort == null) {
            return; // No servlet container -- see the MOCK note above.
        }
        if (managementPort == null) {
            throw new IllegalStateException(
                    "Actuator is being served from the application port (" + applicationPort + "). "
                            + "SecurityConfig permits /actuator/prometheus anonymously on the "
                            + "assumption it is only mapped on a private port, so this would publish "
                            + "the scrape to the internet. Set management.server.port (env "
                            + "MANAGEMENT_SERVER_PORT) to a port no public domain routes to.");
        }
        if (managementPort.equals(applicationPort)) {
            throw new IllegalStateException(
                    "management.server.port and server.port are both " + applicationPort
                            + ". They must differ -- see this class's documentation.");
        }
    }
}
