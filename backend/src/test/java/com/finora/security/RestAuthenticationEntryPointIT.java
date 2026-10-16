package com.finora.security;

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

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The 401 a signed-out request gets, read off the wire from the real embedded server. The unit
 * test's mock response cannot show the container's own default charset; this can. Without an
 * explicit charset Tomcat sent {@code application/json;charset=ISO-8859-1} and the message's em
 * dash went out as a single "?" byte, which is what the apps showed.
 */
class RestAuthenticationEntryPointIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;

    @Test
    void aSignedOutRequestGetsAUtf8Body_withTheEmDashIntact() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<byte[]> response = restTemplate.exchange(
                "/api/v1/transactions/bulk-recategorize", HttpMethod.POST,
                new HttpEntity<>("{}", headers), byte[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getHeaders().getContentType()).isNotNull();
        assertThat(response.getHeaders().getContentType().getCharset()).isEqualTo(StandardCharsets.UTF_8);
        assertThat(new String(response.getBody(), StandardCharsets.UTF_8))
                .contains("Authentication required — please sign in again.");
    }
}
