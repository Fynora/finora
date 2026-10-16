package com.finora.security;

import com.finora.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The JSON bodies written straight to the servlet response, outside Spring MVC's message
 * converters, read off the wire from the real embedded server. The unit tests' mock response
 * cannot show the container's own default charset; this can. Without an explicit charset Tomcat
 * sent {@code application/json;charset=ISO-8859-1}, and the 401 message's em dash went out as a
 * single "?" byte, which is what the apps showed.
 *
 * The two 403 gates are covered the same way in AdminMfaEnforcementIT.
 */
class ServletWrittenJsonBodiesIT extends AbstractIntegrationTest {

    /** Per-IP only (not part of the shared auth ceiling), and a malformed body never reaches the
     *  controller, so tripping it sends no email. */
    private static final String RATE_LIMITED_PATH = "/api/v1/auth/forgot-password";

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private StringRedisTemplate redisTemplate;

    /** Every request here comes from the same loopback address as every other IT's, so the bucket
     *  this class fills is cleared both ways: no inherited count, none left behind. */
    @BeforeEach
    void startFromAnEmptyBucket() {
        clearTheForgotPasswordBucket();
    }

    @AfterEach
    void leaveNoCountBehind() {
        clearTheForgotPasswordBucket();
    }

    private void clearTheForgotPasswordBucket() {
        Set<String> keys = redisTemplate.keys("ratelimit:forgot-password:*");
        if (keys != null && !keys.isEmpty()) redisTemplate.delete(keys);
    }

    private ResponseEntity<byte[]> postJson(String path, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), byte[].class);
    }

    private static void assertUtf8(ResponseEntity<byte[]> response) {
        assertThat(response.getHeaders().getContentType()).isNotNull();
        assertThat(response.getHeaders().getContentType().getCharset()).isEqualTo(StandardCharsets.UTF_8);
    }

    @Test
    void aSignedOutRequestGetsAUtf8Body_withTheEmDashIntact() {
        ResponseEntity<byte[]> response = postJson("/api/v1/transactions/bulk-recategorize", "{}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertUtf8(response);
        assertThat(new String(response.getBody(), StandardCharsets.UTF_8))
                .contains("Authentication required — please sign in again.");
    }

    @Test
    void aRateLimitedRequestGetsAUtf8Body() {
        ResponseEntity<byte[]> response = null;
        for (int i = 0; i < 20; i++) {
            response = postJson(RATE_LIMITED_PATH, "{");
            if (response.getStatusCode().value() == 429) break;
        }

        assertThat(response.getStatusCode().value()).isEqualTo(429);
        assertUtf8(response);
        assertThat(new String(response.getBody(), StandardCharsets.UTF_8))
                .contains("Too many requests. Please wait before trying again.")
                .contains("RATE_LIMITED");
    }
}
