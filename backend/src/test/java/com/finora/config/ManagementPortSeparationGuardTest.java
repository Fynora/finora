package com.finora.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.boot.web.server.WebServer;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A guard that never fires is indistinguishable from no guard, and this one only fires on a
 * misconfiguration nobody will produce on purpose -- so the failing paths are driven directly here
 * rather than waiting to find out in production.
 *
 * <p>{@link ManagementPortIsolationIT} asserts the healthy arrangement against a real server. What
 * that cannot show is what happens when the arrangement is wrong, because a context that refuses to
 * start cannot host a test.
 */
class ManagementPortSeparationGuardTest {

    /** A correctly-configured environment, so the constructor check passes and the runtime one runs. */
    private static ManagementPortSeparationGuard guard() {
        return new ManagementPortSeparationGuard(
                new MockEnvironment().withProperty("server.port", "8080")
                        .withProperty("management.server.port", "9091"));
    }

    private final ManagementPortSeparationGuard guard = guard();

    private static WebServerInitializedEvent event(String namespace, int port) {
        WebServerInitializedEvent event = mock(WebServerInitializedEvent.class);
        WebServerApplicationContext context = mock(WebServerApplicationContext.class);
        WebServer server = mock(WebServer.class);
        when(event.getApplicationContext()).thenReturn(context);
        when(context.getServerNamespace()).thenReturn(namespace);
        when(event.getWebServer()).thenReturn(server);
        when(server.getPort()).thenReturn(port);
        return event;
    }

    @Test
    void separatePortsStartNormally() {
        guard.onWebServerInitialized(event(null, 8080));
        guard.onWebServerInitialized(event("management", 9091));

        assertThatCode(() -> guard.onApplicationReady(mock(ApplicationReadyEvent.class)))
                .doesNotThrowAnyException();
    }

    @Test
    void noManagementServerAtAllRefusesToStart() {
        // The dangerous case: with management.server.port unset, Spring Boot serves actuator from
        // the main context, where SecurityConfig's permitAll on /actuator/prometheus would publish
        // the scrape anonymously to the internet. Nothing else in the system notices.
        guard.onWebServerInitialized(event(null, 8080));

        assertThatIllegalStateException()
                .isThrownBy(() -> guard.onApplicationReady(mock(ApplicationReadyEvent.class)))
                .withMessageContaining("MANAGEMENT_SERVER_PORT");
    }

    @Test
    void aManagementPortEqualToTheApplicationPortRefusesToStart() {
        // Same exposure, reached by setting the variable rather than by omitting it.
        guard.onWebServerInitialized(event(null, 8080));
        guard.onWebServerInitialized(event("management", 8080));

        assertThatIllegalStateException()
                .isThrownBy(() -> guard.onApplicationReady(mock(ApplicationReadyEvent.class)))
                .withMessageContaining("must differ");
    }

    @Test
    void aTestWithNoServletContainerIsLeftAlone() {
        // webEnvironment = MOCK raises no WebServerInitializedEvent at all. There is no listening
        // socket, so there is nothing to protect and nothing to fail.
        assertThatCode(() -> guard.onApplicationReady(mock(ApplicationReadyEvent.class)))
                .doesNotThrowAnyException();
    }

    @Test
    void anAbsentManagementPortIsRefusedBeforeAnythingIsServed() {
        // The constructor runs during bean creation, so this fails while the context is still being
        // built rather than after the server has started accepting requests.
        assertThatIllegalStateException()
                .isThrownBy(() -> new ManagementPortSeparationGuard(
                        new MockEnvironment().withProperty("server.port", "8080")))
                .withMessageContaining("MANAGEMENT_SERVER_PORT");
    }

    @Test
    void aMatchingManagementPortIsRefusedBeforeAnythingIsServed() {
        assertThatIllegalStateException()
                .isThrownBy(() -> new ManagementPortSeparationGuard(
                        new MockEnvironment().withProperty("server.port", "8080")
                                .withProperty("management.server.port", "8080")))
                .withMessageContaining("must differ");
    }

    @Test
    void theRandomPortTestArrangementIsLeftToTheRuntimeCheck() {
        // @SpringBootTest(webEnvironment = RANDOM_PORT) sets both to 0. They are equal, but the real
        // ports are assigned later and are genuinely distinct -- refusing here would fail every
        // integration test in the repository.
        assertThatCode(() -> new ManagementPortSeparationGuard(
                new MockEnvironment().withProperty("server.port", "0")
                        .withProperty("management.server.port", "0")))
                .doesNotThrowAnyException();
    }

    @Test
    void anUnsetServerPortStillDefaultsToTheApplicationPort() {
        // server.port is only ever ${PORT:8080}; a context that does not define it at all still
        // serves on 8080, so the comparison has to assume that rather than skip.
        assertThatIllegalStateException()
                .isThrownBy(() -> new ManagementPortSeparationGuard(
                        new MockEnvironment().withProperty("management.server.port", "8080")))
                .withMessageContaining("must differ");
    }
}
