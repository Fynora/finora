package com.finora.integrations.setu;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.WebhookEvent;
import com.finora.repository.WebhookEventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * The HTTP-level half of the Setu webhook, which {@link AccountAggregatorWebhookControllerTest}
 * cannot see: that test constructs the controller directly, so it stayed green for as long as the
 * route answered 401 to every real caller. {@code SecurityConfig} did not permit
 * {@code /api/v1/webhooks/setu} anonymously the way it did the Razorpay and RevenueCat routes, and
 * Setu sends no Finora bearer token -- the signature header is what replaces authentication
 * (audit, 2026-09-24). These go through {@code TestRestTemplate} so the whole filter chain runs.
 *
 * <p>The dispatcher is mocked: what is under test is that a signed request reaches the controller
 * and is claimed, not what a consent event does downstream (the dispatcher's own tests cover that).
 */
@TestPropertySource(properties = {
        "app.integrations.setu.client-id=it-client-id",
        "app.integrations.setu.client-secret=it-client-secret",
        "app.integrations.setu.webhook-secret=" + AccountAggregatorWebhookControllerIT.SECRET
})
class AccountAggregatorWebhookControllerIT extends AbstractIntegrationTest {

    static final String SECRET = "it-setu-webhook-secret";

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private WebhookEventRepository webhookEventRepository;
    @MockitoBean private AccountAggregatorWebhookDispatcher dispatcher;

    private static String sign(String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    private static HttpHeaders headers(String signature, String eventId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Setu-Signature", signature);
        if (eventId != null) headers.set("X-Setu-Event-Id", eventId);
        return headers;
    }

    @Test
    void anUnauthenticatedPostWithAValidSignatureReachesTheController() throws Exception {
        String consent = "consent-" + UUID.randomUUID();
        String body = "{\"event\":\"consent.revoked\",\"consentHandleId\":\"" + consent + "\"}";
        String eventId = "setu-evt-" + UUID.randomUUID();

        ResponseEntity<String> response = restTemplate.postForEntity("/api/v1/webhooks/setu",
                new HttpEntity<>(body, headers(sign(body), eventId)), String.class);

        assertThat(response.getStatusCode())
                .as("401 here would mean the security layer never let Setu in; the route must be permitAll")
                .isEqualTo(HttpStatus.OK);
        verify(dispatcher).dispatch(eq("consent.revoked"), eq(consent));
        WebhookEvent recorded = webhookEventRepository.findById(eventId).orElseThrow();
        assertThat(recorded.getProvider()).isEqualTo("SETU");
        assertThat(recorded.getStatus()).isEqualTo(WebhookEvent.STATUS_PROCESSED);
    }

    @Test
    void aBadSignatureIsRefusedByTheControllerNotByTheSecurityLayer() throws Exception {
        String body = "{\"event\":\"consent.revoked\",\"consentHandleId\":\"consent-x\"}";

        ResponseEntity<String> response = restTemplate.postForEntity("/api/v1/webhooks/setu",
                new HttpEntity<>(body, headers(sign(body + " "), "setu-evt-" + UUID.randomUUID())), String.class);

        // 400 is the controller's own verdict; 401 would mean the request never reached it.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void aDeliveryWithoutAnEventIdIsKeyedByItsBody_soAReplayIsIgnored() throws Exception {
        String consent = "consent-" + UUID.randomUUID();
        String body = "{\"event\":\"consent.revoked\",\"consentHandleId\":\"" + consent + "\"}";
        HttpEntity<String> request = new HttpEntity<>(body, headers(sign(body), null));

        ResponseEntity<String> first = restTemplate.postForEntity("/api/v1/webhooks/setu", request, String.class);
        ResponseEntity<String> second = restTemplate.postForEntity("/api/v1/webhooks/setu", request, String.class);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(dispatcher, times(1)).dispatch(eq("consent.revoked"), eq(consent));
    }
}
