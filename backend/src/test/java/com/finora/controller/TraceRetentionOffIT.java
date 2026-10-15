package com.finora.controller;

import com.finora.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** With no key configured -- production's state until the owner sets one -- the trace-retention
 *  endpoint does not exist, whatever header a caller sends. */
@TestPropertySource(properties = "app.trace-retention.key=")
class TraceRetentionOffIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;

    @Test
    void withNoKeyConfiguredTheEndpointDoesNotExist() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Trace-Retention-Key", "");
        ResponseEntity<String> empty = restTemplate.exchange("/api/v1/internal/trace-retention/gone", HttpMethod.POST,
                new HttpEntity<>(Map.of("references", List.of(UUID.randomUUID().toString())), headers), String.class);
        headers.set("X-Trace-Retention-Key", "any-key-at-all");
        ResponseEntity<String> some = restTemplate.exchange("/api/v1/internal/trace-retention/gone", HttpMethod.POST,
                new HttpEntity<>(Map.of("references", List.of(UUID.randomUUID().toString())), headers), String.class);

        assertThat(empty.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(some.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
