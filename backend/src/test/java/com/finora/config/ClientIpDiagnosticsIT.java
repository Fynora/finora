package com.finora.config;

import com.finora.AbstractIntegrationTest;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/** Proves the diagnostics filter is wired into the real servlet chain and counts a real request
 *  by labels, without changing the response. Addresses are RFC 5737 / Cloudflare-range fixtures. */
class ClientIpDiagnosticsIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private MeterRegistry registry;

    private double count() {
        Counter counter = registry.find("finora.client_ip.resolution")
                .tag("host", "other").tag("hops", "2")
                .tag("cf_connecting_ip", "differs").tag("first_hop", "differs")
                .counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void aRealRequestIsCountedByLabelsAndStillAnsweredNormally() {
        double before = count();
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Forwarded-For", "203.0.113.7, 162.158.1.1");
        headers.add("CF-Connecting-IP", "203.0.113.7");

        ResponseEntity<String> response = restTemplate.exchange(
                "/health", HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(count()).isEqualTo(before + 1);
    }
}
