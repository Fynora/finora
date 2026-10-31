package com.finora.imports.convert;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Checks a Turnstile token with Cloudflare's {@code siteverify} API
 * (https://developers.cloudflare.com/turnstile/get-started/server-side-validation/).
 *
 * <p>Only the secret and the token are sent. The visitor's IP address is optional in that API and
 * is deliberately left out: Cloudflare saw the visitor when it issued the token, and this call has
 * no reason to tell it again.
 *
 * <p>Timeouts are explicit (a {@code RestClient} has none by default -- see TwoFactorSmsProvider),
 * so a slow Cloudflare cannot hold a request thread.
 */
@Component
public class CloudflareTurnstileVerifier implements TurnstileVerifier {

    private static final Logger log = LoggerFactory.getLogger(CloudflareTurnstileVerifier.class);
    static final String SITEVERIFY_URL = "https://challenges.cloudflare.com/turnstile/v0/siteverify";
    /** Cloudflare's own limit on a token's length. */
    private static final int MAX_TOKEN_LENGTH = 2048;

    private final String secret;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public CloudflareTurnstileVerifier(@Value("${app.converter.turnstile-secret:}") String secret,
                                       ObjectMapper objectMapper) {
        this.secret = secret == null ? "" : secret.trim();
        this.objectMapper = objectMapper;
        ClientHttpRequestFactorySettings timeouts = ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(Duration.ofSeconds(3))
                .withReadTimeout(Duration.ofSeconds(5));
        this.restClient = RestClient.builder()
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(timeouts))
                .build();
    }

    @Override
    public boolean verify(String token) {
        if (secret.isEmpty()) {
            log.warn("Statement converter: no Turnstile secret is configured (TURNSTILE_SECRET), so every conversion is refused");
            return false;
        }
        if (token == null || token.isBlank() || token.length() > MAX_TOKEN_LENGTH) {
            return false;
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("secret", secret);
        form.add("response", token);
        try {
            String body = restClient.post().uri(SITEVERIFY_URL)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(String.class);
            JsonNode reply = body == null ? null : objectMapper.readTree(body);
            return reply != null && reply.path("success").asBoolean(false);
        } catch (Exception e) {
            // Class only: nothing here is statement content, but the reply text is not ours to log.
            log.warn("Statement converter: Turnstile check could not complete ({}); refusing", e.getClass().getSimpleName());
            return false;
        }
    }
}
