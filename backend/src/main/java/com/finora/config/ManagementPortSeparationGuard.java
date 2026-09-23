package com.finora.config;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
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
 * <h2>It checks twice, at two different moments</h2>
 *
 * <p>The <b>constructor</b> compares configured values, while the context is still being built, so
 * a misconfiguration fails before anything is served. It cannot be the only check: under
 * {@code @SpringBootTest(webEnvironment = RANDOM_PORT)} both properties are {@code 0} and the real
 * ports are not chosen yet, and when {@code management.server.port} is absent there is no value to
 * compare at all -- absence is precisely the dangerous case, because Spring Boot then serves
 * actuator from the main context.
 *
 * <p>The <b>bound-port</b> check on {@link ApplicationReadyEvent} is therefore the authoritative
 * one, and answers both of those: a separate management server raises its own
 * {@link WebServerInitializedEvent} under the {@code management} namespace, so its absence is the
 * signal, and two random test ports are really distinct by then. Its weakness is timing -- the
 * server is already accepting requests when it runs -- which is what the constructor covers.
 *
 * <p>Tests with {@code webEnvironment = MOCK} start no web server, raise no event, and are skipped
 * by the runtime half -- there is no listening socket for anyone to reach.
 */
@Component
public class ManagementPortSeparationGuard {

    private static final String MANAGEMENT_NAMESPACE = "management";

    private Integer applicationPort;
    private Integer managementPort;

    /**
     * The configured-value check, run while the context is still being built.
     *
     * <p>The bound-port check below is the authoritative one, but it runs on
     * {@link ApplicationReadyEvent} -- after the server is already accepting requests. In the
     * misconfigured state that is a window, however brief, in which the scrape is genuinely being
     * served to whoever asks. Failing during bean creation closes it for the two cases that are
     * visible from configuration alone, which are both of the ones a human actually causes.
     *
     * <p>It cannot replace the event check. When {@code management.server.port} is absent Spring
     * Boot serves actuator from the main context, and no property anywhere records that -- absence
     * is the whole signal, and only the missing {@link WebServerInitializedEvent} proves it at
     * runtime. The two checks overlap on purpose.
     */
    public ManagementPortSeparationGuard(Environment environment) {
        String server = environment.getProperty("server.port", "8080");
        String management = environment.getProperty("management.server.port");

        // Both zero is @SpringBootTest(webEnvironment = RANDOM_PORT): the real, distinct ports are
        // assigned during startup, so there is nothing to compare yet and the event check covers it.
        if ("0".equals(server) && "0".equals(management)) {
            return;
        }
        if (management == null) {
            throw new IllegalStateException(
                    "management.server.port is not set, so actuator would be served from the "
                            + "application port. SecurityConfig permits /actuator/prometheus "
                            + "anonymously on the assumption it is only mapped on a private port. "
                            + "Set MANAGEMENT_SERVER_PORT to a port no public domain routes to.");
        }
        if (management.equals(server)) {
            throw new IllegalStateException(
                    "management.server.port and server.port are both " + server
                            + ". They must differ -- see this class's documentation.");
        }
    }

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
