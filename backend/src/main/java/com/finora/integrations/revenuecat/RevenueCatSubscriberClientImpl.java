package com.finora.integrations.revenuecat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Optional;

/**
 * The real RevenueCat call: one GET per lapsed store subscription, once a day at most. Thin on
 * purpose, like the Razorpay gateway: the choice it feeds is tested against
 * {@link RevenueCatSubscriberClient#pick} and a stubbed client.
 *
 * <p>Timeouts are explicit (a {@code RestClient} has none by default -- see TwoFactorSmsProvider).
 * No {@code X-Platform} header: RevenueCat asks for it to be left out of informational reads with a
 * secret key, since it updates the customer's last-seen time.
 */
@Component
public class RevenueCatSubscriberClientImpl implements RevenueCatSubscriberClient {

    private static final String BASE_URL = "https://api.revenuecat.com/v1";

    private final RevenueCatProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public RevenueCatSubscriberClientImpl(RevenueCatProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        ClientHttpRequestFactorySettings timeouts = ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(Duration.ofSeconds(5))
                .withReadTimeout(Duration.ofSeconds(10));
        this.restClient = RestClient.builder()
                .baseUrl(BASE_URL)
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(timeouts))
                .build();
    }

    @Override
    public boolean isConfigured() {
        return properties.isApiConfigured();
    }

    @Override
    public Optional<StoreSubscription> subscription(String appUserId, String originalTransactionId, String productId) {
        if (!isConfigured()) throw new IllegalStateException("REVENUECAT_SECRET_API_KEY is not set.");
        String body = restClient.get()
                .uri("/subscribers/{appUserId}", appUserId)
                .header("Authorization", "Bearer " + properties.getSecretApiKey())
                .retrieve()
                .body(String.class);
        try {
            JsonNode json = objectMapper.readTree(body == null ? "{}" : body);
            return RevenueCatSubscriberClient.pick(json, originalTransactionId, productId);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("RevenueCat returned a body that is not JSON.", e);
        }
    }
}
