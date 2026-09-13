package com.finora.integrations.setu;

import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * Placeholder bean -- exists only so the Spring context has something to inject wherever
 * SetuDataFetchGateway is wired, the same reasoning as SetuConsentGatewayImpl (Plan 1: an
 * interface with no implementing bean breaks the whole application, not just this feature). A real
 * implementation (HTTP client, request signing, ECDH decrypt of Setu's FI-data payload) needs real
 * Setu sandbox credentials to build correctly and is tracked as a named follow-up, not guessed here.
 */
@Component
public class SetuDataFetchGatewayImpl implements SetuDataFetchGateway {

    private final SetuProperties properties;

    public SetuDataFetchGatewayImpl(SetuProperties properties) {
        this.properties = properties;
    }

    @Override
    public boolean isConfigured() {
        return properties.isConfigured();
    }

    @Override
    public SetuFiDataFetchResult fetchTransactions(String consentHandleId, LocalDate from, LocalDate to) {
        throw new UnsupportedOperationException(
                "Real Setu FI-data fetch is not implemented yet -- needs sandbox credentials.");
    }
}
