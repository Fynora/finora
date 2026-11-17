package com.finora.config;

import com.finora.AbstractIntegrationTest;
import org.apache.catalina.connector.Connector;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.embedded.tomcat.TomcatWebServer;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;

import java.net.InetAddress;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The integration-test server listens on loopback only (see {@link AbstractIntegrationTest}'s note
 * on its {@code @SpringBootTest}). Bound to the wildcard address, its random port could coincide with
 * another local process on 127.0.0.1, and every "localhost" request of a test would reach that
 * process instead.
 */
class IntegrationServerLoopbackIT extends AbstractIntegrationTest {

    @Autowired private ServletWebServerApplicationContext context;
    @Autowired private TestRestTemplate restTemplate;

    @Test
    void theApplicationServerIsBoundToLoopback() {
        Connector connector = ((TomcatWebServer) context.getWebServer()).getTomcat().getConnector();
        Object address = connector.getProperty("address");

        assertThat(address).isInstanceOf(InetAddress.class);
        assertThat(((InetAddress) address).isLoopbackAddress()).as(String.valueOf(address)).isTrue();
    }

    @Test
    void localhostStillReachesTheApplication_andTheManagementServer() {
        // The relative URL resolves to http://localhost:<port>: still this app once loopback-bound.
        assertThat(restTemplate.getForEntity("/health", String.class).getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(restTemplate.getForEntity(actuatorUrl("health"), String.class).getStatusCode().is2xxSuccessful())
                .isTrue();
    }
}
